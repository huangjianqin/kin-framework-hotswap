package org.kin.framework.hotswap.jclass;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import org.kin.agent.JavaDynamicAgent;
import org.kin.framework.collection.Tuple;
import org.kin.framework.utils.CollectionUtils;
import org.kin.framework.utils.ExceptionUtils;
import org.kin.framework.utils.SysUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author huangjianqin
 * @date 2018/2/3
 */
public final class ClassHotswap implements ClassHotswapMBean {
    private static final Logger log = LoggerFactory.getLogger(ClassHotswap.class);
    /** jar包后缀 */
    private static final String JAR_SUFFIX = ".jar";
    /** class文件后缀 */
    private static final String CLASS_SUFFIX = ".class";
    /** zip压缩包后缀 */
    private static final String ZIP_SUFFIX = ".zip";
    /**
     * 热更class文件放另外一个目录
     * 开发者指定, 也可以走配置
     */
    public static final String CLASSPATH;
    /**
     * java agent jar路径
     */
    public static final String AGENT_PATH;
    /** 热加载过的class文件信息, key -> class name */
    private final Map<String, ClassFileInfo> name2ClassFileInfo = new HashMap<>();

    static {
        CLASSPATH = SysUtils.getSysProperty("kin.hotswap.classpath", "hotswap/classes");
        log.info("java agent:classpath:{}", CLASSPATH);

        AGENT_PATH = SysUtils.getSysProperty("kin.hotswap.agent.dir", "hotswap/").concat("kin-java-agent.jar");
        log.info("java agent:jarPath:{}", AGENT_PATH);
    }

    /** 单例 */
    private static ClassHotswap INSTANCE;

    public static ClassHotswap instance() {
        if (Objects.isNull(INSTANCE)) {
            synchronized (ClassHotswap.class) {
                if (Objects.nonNull(INSTANCE)) {
                    return INSTANCE;
                }
                INSTANCE = new ClassHotswap();
            }
        }
        return INSTANCE;
    }

    private ClassHotswap() {
        initMBean();
    }

    /**
     * 初始化JMX监控
     */
    private void initMBean() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(this + ":type=JavaAgentHotswap");
            mBeanServer.registerMBean(this, name);
        } catch (MalformedObjectNameException | NotCompliantMBeanException | InstanceAlreadyExistsException | MBeanRegistrationException e) {
            ExceptionUtils.throwExt(e);
        }
    }

    /**
     * 热更新逻辑
     */
    public synchronized boolean hotswap(List<Path> changedPaths) {
        //开始时间
        long startTime = System.currentTimeMillis();
        log.info("hotswap start...");
        try {
            //key -> class name, value -> 该类class文件信息
            Map<String, ClassFileInfo> name2ClassFileInfo = new HashMap<>(changedPaths.size());
            //新类和其class文件内容
            List<Tuple<String, byte[]>> newClassNameAndBytesList = new ArrayList<>(changedPaths.size());
            //待热更新的class定义
            List<ClassDefinition> classDefinitions = new ArrayList<>(changedPaths.size());
            ByteArrayOutputStream baos = null;
            try {
                for (Path changedPath : changedPaths) {
                    //文件路径
                    String filePath = changedPath.toString();
                    try {
                        if (Files.isDirectory(changedPath) ||
                                Files.isHidden(changedPath) ||
                                !Files.isReadable(changedPath)) {
                            //过滤目录, 隐藏文件, 不可读文件
                            continue;
                        }

                        String changedFileName = changedPath.getFileName().toString();
                        if (!changedFileName.endsWith(CLASS_SUFFIX) && !changedFileName.endsWith(ZIP_SUFFIX)) {
                            //只允许.class和.zip
                            continue;
                        }

                        long fileLastModifiedMs = Files.getLastModifiedTime(changedPath).toMillis();

                        if (changedFileName.endsWith(ZIP_SUFFIX)) {
                            if (Objects.isNull(baos)) {
                                //default 64k
                                baos = new ByteArrayOutputStream(65536);
                            } else {
                                //复用前先reset
                                baos.reset();
                            }
                            parseZip(changedPath, baos, name2ClassFileInfo, classDefinitions, newClassNameAndBytesList);
                        } else {
                            byte[] bytes = Files.readAllBytes(changedPath);
                            parseClassFile(filePath, fileLastModifiedMs, bytes, name2ClassFileInfo, classDefinitions, newClassNameAndBytesList);
                        }
                    } catch (Exception e) {
                        log.error(String.format("file '%s' parse error, hotswap fail", filePath), e);
                        return false;
                    }
                }
            } finally {
                if (Objects.nonNull(baos)) {
                    try {
                        baos.close();
                    } catch (IOException e) {
                        log.error("", e);
                    }
                }
            }

            // 当前进程pid
            String name = ManagementFactory.getRuntimeMXBean().getName();
            String pid = name.split("@")[0];
            log.debug("now pid is '{}'", pid);

            // 虚拟机加载
            VirtualMachine vm = null;
            try {
                vm = VirtualMachine.attach(pid);
                //JavaDynamicAgent所在的jar包
                //app jar包与agent jar包同一路径
                vm.loadAgent(AGENT_PATH);

                //先加载新类
                loadNewClass(newClassNameAndBytesList);

                //重新定义类
                JavaDynamicAgent.getInstrumentation().redefineClasses(classDefinitions.toArray(new ClassDefinition[0]));

                //更新元数据
                this.name2ClassFileInfo.putAll(name2ClassFileInfo);

                //success log
                for (Tuple<String, byte[]> tuple : newClassNameAndBytesList) {
                    log.info("load new class '{}' success", tuple.first());
                }

                for (ClassDefinition classDefinition : classDefinitions) {
                    log.info("redefine loaded class '{}' success", classDefinition.getDefinitionClass().getName());
                }

                //删除热更类文件
                Path rootPath = Paths.get(CLASSPATH);
                Files.list(rootPath).forEach(childpath -> {
                    try {
                        Files.deleteIfExists(childpath);
                    } catch (IOException e) {
                        ExceptionUtils.throwExt(e);
                    }
                });
                return true;
            } catch (Exception e) {
                log.error("hotswap fail, due to", e);
            } finally {
                if (vm != null) {
                    try {
                        vm.detach();
                    } catch (IOException e) {
                        ExceptionUtils.throwExt(e);
                    }
                }
            }
        } finally {
            //结束时间
            long endTime = System.currentTimeMillis();
            log.info("...hotswap finish, cost {} ms", endTime - startTime);
        }

        return false;
    }

    /**
     * 解析class文件, 根据规则过滤并将合法的class文件内容转换成{@link ClassDefinition}实例, 并添加到{@code classDefinitions}
     * 如果是新类, 则添加到{@code newClassNameAndBytesList}
     *
     * @param classFilePath            class文件路径
     * @param classFileLastModifiedMs  class文件上次修改时间
     * @param bytes                    class文件内容
     * @param name2ClassFileInfo       新的热加载过的class文件信息
     * @param classDefinitions         待热更新的class定义
     * @param newClassNameAndBytesList 新类和其class文件内容
     */
    private void parseClassFile(String classFilePath, long classFileLastModifiedMs,
                                byte[] bytes,
                                Map<String, ClassFileInfo> name2ClassFileInfo,
                                List<ClassDefinition> classDefinitions,
                                List<Tuple<String, byte[]>> newClassNameAndBytesList) throws ConstantPoolException, IOException {
        log.info("file '{}' checking...", classFilePath);

        //从class文件字节码中读取className
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        ClassFile cf = ClassFile.read(dis);
        String className = cf.getName().replaceAll("/", "\\.");
        dis.close();

        //原class文件信息
        ClassFileInfo old = this.name2ClassFileInfo.get(className);
        //过滤没有变化的文件(通过文件修改时间)
        if (old != null && old.getLastModifyTime() == classFileLastModifiedMs) {
            log.info("file '{}' is ignored, because it's file modified time is not changed", classFilePath);
            return;
        }

        //封装成class文件信息
        ClassFileInfo cfi = new ClassFileInfo(classFilePath, className, bytes, classFileLastModifiedMs);
        //检查类名
        if (old != null && !old.getClassName().equals(cfi.getClassName())) {
            log.info("file '{}' is ignored, because it's class name is not the same with the origin", classFilePath);
            return;
        }

        //检查内容
        if (old != null && !old.getMd5().equals(cfi.getMd5())) {
            log.info("file '{}' is ignored, because it's content is not changed", classFilePath);
            return;
        }

        log.info("file '{}' pass check, it's class name is {}", classFilePath, className);
        name2ClassFileInfo.put(className, cfi);

        Class<?> c;
        try {
            c = Class.forName(className);
            classDefinitions.add(new ClassDefinition(c, bytes));
        } catch (ClassNotFoundException e) {
            //load不到class, 则是新类
            newClassNameAndBytesList.add(new Tuple<>(className, bytes));
        }
    }

    /**
     * 解析zip包, 该zip包可能包含多个class文件.
     * 之所以需要打包成zip, 因为想批量redefine, 这样子可以保证同时热更新成功, 或者同时热更新失败, 不会污染运行时环境
     * 不打包成zip, 有可能因为网络传输延迟, 想要热更新的class文件, 分批到达, 这样子框架会认为是多次热更新, 这样子无法达到预期效果, 还很有可能污染运行时环境
     *
     * @param changedPath              包含class文件的zip路径
     * @param baos                     复用的{@link ByteArrayOutputStream}, 单线程操作, 复用可以减少内存分配
     * @param name2ClassFileInfo       新的热加载过的class文件信息
     * @param classDefinitions         待热更新的class定义
     * @param newClassNameAndBytesList 新类和其class文件内容
     */
    private void parseZip(Path changedPath, ByteArrayOutputStream baos,
                          Map<String, ClassFileInfo> name2ClassFileInfo,
                          List<ClassDefinition> classDefinitions,
                          List<Tuple<String, byte[]>> newClassNameAndBytesList) throws IOException, ConstantPoolException {
        String separator = changedPath.getFileSystem().getSeparator();
        //模拟uri的路径格式
        String zipFilePath = changedPath + "!" + separator;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(changedPath))) {
            ZipEntry entry;
            byte[] buffer = null;
            while (Objects.nonNull((entry = zis.getNextEntry()))) {
                if (entry.isDirectory()) {
                    //过滤目录
                    zis.closeEntry();
                    continue;
                }

                String fileName = entry.getName();
                if (!fileName.endsWith(CLASS_SUFFIX)) {
                    //过滤非class文件
                    zis.closeEntry();
                    continue;
                }
                if (Objects.isNull(buffer)) {
                    //lazy init
                    buffer = new byte[2048];
                }

                String classFilePath = zipFilePath + fileName;
                //获取文件修改时间
                long classFileLastModifiedMs = entry.getLastModifiedTime().toMillis();

                //读取class文件内容
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }

                //解析class文件
                parseClassFile(classFilePath, classFileLastModifiedMs, baos.toByteArray(), name2ClassFileInfo, classDefinitions, newClassNameAndBytesList);
                //close zip entry
                zis.closeEntry();
                //重置
                baos.reset();
            }
        }
    }

    /**
     * 加载新类
     *
     * @param newClassNameAndBytesList 新类和其class文件内容
     */
    private void loadNewClass(List<Tuple<String, byte[]>> newClassNameAndBytesList) throws NoSuchMethodException {
        if (CollectionUtils.isEmpty(newClassNameAndBytesList)) {
            return;
        }

        //获取context class loader
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        //基于反射, 获取class loader定义class方法
        Method defineClassCaller = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
        if (!defineClassCaller.isAccessible()) {
            defineClassCaller.setAccessible(true);
        }

        for (Tuple<String, byte[]> tuple : newClassNameAndBytesList) {
            String className = tuple.first();
            byte[] bytes = tuple.second();
            try {
                //load new class
                defineClassCaller.invoke(classLoader, className, bytes, 0, bytes.length);
            } catch (Exception e) {
                throw new ClassHotswapException(String.format("load new class '%s' error", className), e);
            }
        }
    }

    @Override
    public List<ClassFileInfo> getClassFileInfo() {
        return new ArrayList<>(name2ClassFileInfo.values());
    }
}
