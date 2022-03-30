package org.kin.framework.hotswap;

import org.kin.framework.utils.SPI;

/**
 * @author huangjianqin
 * @date 2018/10/31
 */
@SPI(alias = "hotswapListener")
@FunctionalInterface
public interface HotswapListener {
    /**
     * 热更新完成后触发, 暴露接口给开发者自定义逻辑
     */
    void afterHotswap();
}
