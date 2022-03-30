package org.kin.framework.hotswap.jclass;

import java.util.List;

/**
 * @author huangjianqin
 * @date 2019/3/1
 */
public interface ClassHotswapMBean {
    /**
     * 用于JMX监控
     *
     * @return 返回类信息
     */
    List<ClassFileInfo> getClassFileInfo();
}
