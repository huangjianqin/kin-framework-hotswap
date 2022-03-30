package org.kin.framework.hotswap.jclass;
/**
 * Created by huangjianqin on 2018/2/2.
 */

/*
     打包的时候记得要设置MANIFEST.MF
     <manifestEntries>
        <Agent-Class>org.kin.agent.JavaDynamicAgent</Premain-Class>
        <Can-Redefine-Classes>true</Can-Redefine-Classes>
        <Can-Retransform-Classes>true</Can-Retransform-Classes>
     </manifestEntries>

     实现原理是：
     1. 通过pid获得虚拟机对象
     2. 通过连接虚拟机加载代理jar包,这样就调用到agentmain,获取得到Instrumentation
     3. 基于Instrumentation接口可以实现JDK的代理机制,从而实现对类进行动态重新定义。

     注意：com.sun.tools.attach.VirtualMachine的jar包是 jdk下lib中的tools.jar,所以项目中要引用到这个jar包,而且因为涉及到底层虚拟机,windows和linux机器这个jar不同

     因此，整个流程就是：
     1. 项目中引用 jdk/lib/tools.jar,否则无法使用VirtualMachine类
     2. 项目中引用 kin-java-agent.jar,它提供了agentmain接口
     3. 代码实现动态增加JDK代理


    不适用的情况:
        1. 实例方法签名修改(增删方法, 参数数量或类型改变)
        2. java compiler会编译生成实例方法的lambda

    适用情况(特殊):
        1. import原本没有的类, 并实例化
        2. 修改static方法
 */