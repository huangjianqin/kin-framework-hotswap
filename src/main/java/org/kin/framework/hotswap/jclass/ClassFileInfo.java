package org.kin.framework.hotswap.jclass;

import org.kin.framework.utils.ExceptionUtils;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @author huangjianqin
 * @date 2019/3/1
 */
public class ClassFileInfo {
    /** class文件路径 */
    private final String filePath;
    /** class name, 从class文件解析出来 */
    private final String className;
    /** class文件修改时间 */
    private final long lastModifyTime;
    /** class文件内容md5编码 */
    private final String md5;

    public ClassFileInfo(String filePath, String className, byte[] bytes, long lastModifyTime) {
        this.filePath = filePath;
        this.className = className;
        this.lastModifyTime = lastModifyTime;
        this.md5 = this.md5(bytes);
    }

    private String md5(byte[] bytes) {
        try {
            MessageDigest me = MessageDigest.getInstance("MD5");
            me.update(bytes);
            BigInteger bi = new BigInteger(1, me.digest());
            return bi.toString(16).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            ExceptionUtils.throwExt(e);
        }

        throw new ClassHotswapException("encounter unknown error");
    }

    //getter
    public String getFilePath() {
        return filePath;
    }

    public String getClassName() {
        return className;
    }

    public long getLastModifyTime() {
        return lastModifyTime;
    }

    public String getMd5() {
        return md5;
    }

    @Override
    public String toString() {
        return "ClassFileInfo{" +
                "filePath='" + filePath + '\'' +
                ", className='" + className + '\'' +
                ", lastModifyTime=" + lastModifyTime +
                ", md5='" + md5 + '\'' +
                '}';
    }
}
