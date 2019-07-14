package org.apache.dubbo.common.utils;

import java.util.regex.Pattern;

public class Test {
    public static void main(String[] args) {
        String tem = "file:/path/to/file.txt";
        int i = tem.indexOf(":/");
        System.out.println(i);
        String tem1 = tem.substring(i+1);
        System.out.println(tem1);


        final Pattern INTEGER_PATTERN = Pattern.compile("([_.a-zA-Z0-9][-_.a-zA-Z0-9]*)[=](.*)");
//        final Pattern INTEGER_PATTERN = Pattern.compile("[_.a-zA-Z0-9]*");
        String value = ".a=123aa";
//        String value = "";
        boolean tem3 = INTEGER_PATTERN.matcher(value).matches();
//        boolean tem2 = INTEGER_PATTERN.matcher(value).find();
        System.out.println(tem3);
//        System.out.println(tem2);

        String aa = "abc";
        String bb = "abcdef";
        System.out.println(bb.contains(aa));

        String pattern = "*#06*abd";
        int cc = pattern.lastIndexOf('*');
        System.out.println(cc);
    }
}
