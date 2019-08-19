package org.apache.dubbo.common.utils;

// 这个类是配合Wrapper类做测试用的，可以看到执行过程的结果
public class Stu {
    private String stuName;
    private Integer age;
    private boolean isRich;

    public void show(){
        System.out.println("hehe");
    }

    public void show(int aa){
        System.out.println("hehe");
    }

    public boolean isRich() {
        return isRich;
    }

    public void setRich(boolean rich) {
        isRich = rich;
    }

    public String getStuName() {
        return stuName;
    }

    public void setStuName(String stuName) {
        this.stuName = stuName;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }
}
