package org.kin.framework.hotswap;

/**
 * 启动后观察一会输出, 然后将hotswap/Test.class复制到classes目录下, 则可以看到输出从222变成111
 * Created by huangjianqin on 2018/10/31.
 */
public class HotSwapMain {
    public static void main(String[] args) {
        Test test = new Test();
        FileMonitor monitor = FileMonitor.instance();
        int i = 0;
        while (true) {
            try {
                Thread.sleep(5000);
                System.out.println(test.message());
                i++;
                if (i % 10 == 0) {
                    test = new Test();
                    System.out.println("new obj");
                }
                try {
                    Class<?> class2Class = Class.forName("org.kin.framework.hotswap.NewClass2");
                    System.out.println(class2Class.newInstance());
                } catch (ClassNotFoundException e) {
                    System.err.println("找不到NewClass2");
                } catch (InstantiationException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
