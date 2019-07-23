package org.apache.dubbo.common.utils;

public class TestImpl implements TestInterface {
    @Override
    public void show(String name, String addr, int age) {
        System.out.println(name +"==="+ addr+"==="+age);
    }

    @Override
    public void showb() {
        System.out.println("hehe");
    }
}
