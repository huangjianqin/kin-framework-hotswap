package org.kin.framework.hotswap;

import java.io.InputStream;

/**
 * 文件热更新父类
 *
 * @author huangjianqin
 * @date 2018/2/1
 */
public abstract class AbstractFileReloadable implements Reloadable {
    /** 文件路径 */
    private final String filePath;

    public AbstractFileReloadable(String filePath) {
        this.filePath = filePath;
        FileMonitor.instance().monitorFile(filePath, this);
    }

    public String getFilePath() {
        return filePath;
    }

    /**
     * 文件重载
     *
     * @param is 文件流
     */
    protected abstract void reload(InputStream is);
}
