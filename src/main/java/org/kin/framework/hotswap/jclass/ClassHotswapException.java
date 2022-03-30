package org.kin.framework.hotswap.jclass;

/**
 * @author huangjianqin
 * @date 2022/2/23
 */
public final class ClassHotswapException extends RuntimeException {
    private static final long serialVersionUID = -5832511791747247728L;

    public ClassHotswapException(String message) {
        super(message);
    }

    public ClassHotswapException(String message, Throwable cause) {
        super(message, cause);
    }
}
