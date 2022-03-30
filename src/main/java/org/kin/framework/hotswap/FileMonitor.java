package org.kin.framework.hotswap;

import org.kin.framework.Closeable;
import org.kin.framework.concurrent.ExecutionContext;
import org.kin.framework.hotswap.jclass.ClassHotswap;
import org.kin.framework.utils.ExceptionUtils;
import org.kin.framework.utils.ExtensionLoader;
import org.kin.framework.utils.SysUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件监听器
 * 单例模式
 * 利用nio 新api监听文件变换
 * 该api底层本质上是监听了操作系统的文件系统触发的文件更改事件
 * <p>
 * 异步热加载文件 同步类热更新
 *
 * @author huangjianqin
 * @date 2018/2/1
 */
public class FileMonitor extends Thread implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(FileMonitor.class);
    /** 文件变化监听服务, 基于文件系统事件触发 */
    private WatchService watchService;
    /** hash(file name) -> Reloadable 实例 */
    private Map<Integer, AbstractFileReloadable> monitorItems;
    /** 异步热加载文件以及类热更新执行线程 */
    private ExecutionContext executionContext;
    private volatile boolean isStopped = false;
    /** 热更新listeners */
    private final List<HotswapListener> listeners = ExtensionLoader.getExtensions(HotswapListener.class);

    /** 单例 */
    private static final FileMonitor INSTANCE = new FileMonitor();

    static {
        INSTANCE.start();
    }

    public static FileMonitor instance() {
        return INSTANCE;
    }

    private FileMonitor() {
        super("fileMonitor");
    }

    private void init() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();

        monitorItems = new ConcurrentHashMap<>();
        executionContext = ExecutionContext.elastic(1, SysUtils.CPU_NUM, "fileReload");

        //监听热更class存储目录
        Path classesPath = Paths.get(ClassHotswap.CLASSPATH);
        classesPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

        monitorJVMClose();
    }

    @Override
    public synchronized void start() {
        try {
            init();
        } catch (IOException e) {
            ExceptionUtils.throwExt(e);
        }
        super.start();
    }

    @Override
    public void run() {
        log.info("file monitor start");
        while (!isStopped && !Thread.currentThread().isInterrupted()) {
            List<Path> changedClasses = new ArrayList<>();
            try {
                WatchKey key = watchService.take();
                //变化的路径
                Path parentPath = (Path) key.watchable();
                List<WatchEvent<?>> events = key.pollEvents();
                events.forEach(event -> {
                    //变化item的名字(文件名或者文件夹名)
                    String itemName = event.context().toString();
                    int hashKey = itemName.hashCode();
                    //真实路径
                    String parentPathStr = parentPath.toString();
                    Path path = Paths.get(parentPathStr, itemName);
                    log.info("'{}' changed", path);

                    try {
                        if (Files.isHidden(path) ||
                                !Files.isReadable(path) ||
                                Files.isDirectory(path)) {
                            //过滤隐藏文件, 不可读文件和目录
                            return;
                        }
                    } catch (IOException e) {
                        ExceptionUtils.throwExt(e);
                    }

                    if (parentPathStr.contains(ClassHotswap.CLASSPATH)) {
                        //在热更类目录下, 都认为是待热更class文件或者含class文件的zip文件
                        changedClasses.add(path);
                    } else {
                        //处理文件热更新
                        AbstractFileReloadable fileReloadable = monitorItems.get(hashKey);
                        if (fileReloadable != null) {
                            executionContext.execute(() -> {
                                try {
                                    long startTime = System.currentTimeMillis();
                                    try (InputStream is = new FileInputStream(path.toFile())) {
                                        fileReloadable.reload(is);
                                    }
                                    long endTime = System.currentTimeMillis();
                                    log.info("file reload '{}' finished, time cost {} ms", path, endTime - startTime);
                                } catch (IOException e) {
                                    log.error(String.format("file '%s' reload encounter error", path), e);
                                }
                            });
                        }
                    }
                });
                //重置状态，让key等待事件
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (changedClasses.size() > 0) {
                //类热更新
                executionContext.execute(() -> {
                    if (ClassHotswap.instance().hotswap(changedClasses)) {
                        //延迟5s执行
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                for (HotswapListener listener : listeners) {
                                    try {
                                        listener.afterHotswap();
                                    } catch (Exception e) {
                                        log.error("encounter error, when trigger HotswapListener", e);
                                    }
                                }
                            }
                        }, 5 * 1000);
                    }
                });
            }
        }
        log.info("file monitor shutdown");
    }

    /**
     * shutdown
     */
    public void shutdown() {
        if (isStopped) {
            return;
        }

        isStopped = true;
        //中断监控线程, 让本线程退出
        interrupt();
        try {
            watchService.close();
        } catch (IOException e) {
            ExceptionUtils.throwExt(e);
        }
        executionContext.shutdown();
        //help GC
        monitorItems = null;
    }

    /**
     * 状态检查
     */
    private void checkStatus() {
        if (isStopped) {
            throw new IllegalStateException("file monitor has been shutdown");
        }
    }

    /**
     * 监听文件变化
     */
    public void monitorFile(String pathStr, AbstractFileReloadable fileReloadable) {
        checkStatus();
        Path path = Paths.get(pathStr);
        monitorFile(path, fileReloadable);
    }

    /**
     * 监听文件变化
     */
    public void monitorFile(Path path, AbstractFileReloadable fileReloadable) {
        checkStatus();
        if (!Files.isDirectory(path)) {
            try {
                monitorFile0(path.getParent(), path.getFileName().toString(), fileReloadable);
            } catch (IOException e) {
                ExceptionUtils.throwExt(e);
            }
        } else {
            throw new IllegalStateException("monitor file is a directory");
        }
    }

    /**
     * 监听文件变化
     */
    private void monitorFile0(Path file, String itemName, AbstractFileReloadable fileReloadable) throws IOException {
        int key = itemName.hashCode();
        AbstractFileReloadable old = monitorItems.putIfAbsent(key, fileReloadable);
        if (Objects.nonNull(old)) {
            throw new IllegalStateException(String.format("file '%s' has been monitored", file));
        }
        file.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
    }

    @Override
    public void close() {
        shutdown();
    }
}
