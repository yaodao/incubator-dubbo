/*
 * The MIT License
 *
 * Copyright (c) 2013 Edin Dazdarevic (edin.dazdarevic@gmail.com)
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 **/
package org.apache.dubbo.common.utils;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that enables to get an IP range from CIDR specification. It supports
 * both IPv4 and IPv6.
 * <p>
 * From https://github.com/edazdarevic/CIDRUtils/blob/master/CIDRUtils.java
 */
public class CIDRUtils {
    private final String cidr;

    private InetAddress inetAddress;
    private InetAddress startAddress;
    private InetAddress endAddress;
    private final int prefixLength;


    public CIDRUtils(String cidr) throws UnknownHostException {

        this.cidr = cidr;

        /* split CIDR to address and prefix part */
        if (this.cidr.contains("/")) {
            int index = this.cidr.indexOf("/");
            // 分成单个ip地址和网络号占用的位数
            String addressPart = this.cidr.substring(0, index);
            String networkPart = this.cidr.substring(index + 1);

            inetAddress = InetAddress.getByName(addressPart);
            prefixLength = Integer.parseInt(networkPart);

            calculate();
        } else {
            throw new IllegalArgumentException("not an valid CIDR format!");
        }
    }


    private void calculate() throws UnknownHostException {

        ByteBuffer maskBuffer;
        int targetSize;
        if (inetAddress.getAddress().length == 4) {
            maskBuffer =
                    ByteBuffer
                            .allocate(4)
                            .putInt(-1);
            targetSize = 4;
        } else {
            maskBuffer = ByteBuffer.allocate(16)
                    .putLong(-1L)
                    .putLong(-1L);
            targetSize = 16;
        }

        BigInteger mask = (new BigInteger(1, maskBuffer.array())).not();
        mask = mask.shiftRight(prefixLength);
        ByteBuffer buffer = ByteBuffer.wrap(inetAddress.getAddress());
        BigInteger ipVal = new BigInteger(1, buffer.array());

        BigInteger startIp = ipVal.and(mask);
        BigInteger endIp = startIp.add(mask.not());

        byte[] startIpArr = toBytes(startIp.toByteArray(), targetSize);
        byte[] endIpArr = toBytes(endIp.toByteArray(), targetSize);

        // 成员变量cidr对应的起始地址和结束地址
        this.startAddress = InetAddress.getByAddress(startIpArr);
        this.endAddress = InetAddress.getByAddress(endIpArr);

    }

    /**
     * 将数组array 扩容到targetSize大小 (用0填充, 填充从数组的0下标开始)
     * @param array
     * @param targetSize 用于指定最后生成的byte数组的大小
     * @return 返回扩容后的byte数组
     */
    private byte[] toBytes(byte[] array, int targetSize) {
        int counter = 0;
        List<Byte> newArr = new ArrayList<Byte>();
        // 参数array原样复制给newArr (后面的判断条件是必须有的, 因为targetSize可以大于array.size)
        while (counter < targetSize && (array.length - 1 - counter >= 0)) {
            newArr.add(0, array[array.length - 1 - counter]);
            counter++;
        }

        // 填充newArr, 使其达到参数targetSize指定的大小
        int size = newArr.size();
        for (int i = 0; i < (targetSize - size); i++) {
            // 从0下标开始填充
            newArr.add(0, (byte) 0);
        }

        byte[] ret = new byte[newArr.size()];
        // 原样复制newArr中的元素到ret中
        for (int i = 0; i < newArr.size(); i++) {
            ret[i] = newArr.get(i);
        }
        return ret;
    }

    public String getNetworkAddress() {

        return this.startAddress.getHostAddress();
    }

    public String getBroadcastAddress() {
        return this.endAddress.getHostAddress();
    }

    // 判断给定的ip地址是否在startAddress和endAddress之间 (这提供了个思路, 把ip转成BigInteger, 再判断该地址是否在指定区间)
    public boolean isInRange(String ipAddress) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(ipAddress);
        // 这个函数作用就是将ip地址转换成一个大的整数,
        // 由startAddress.getAddress()得到ip地址的字节数组, 但是byte值范围是-128~127, 无法存入类似192, 255这种值, 但是byte按无符号解读是可以有192, 255这种值的
        // 这种BigInteger的构造方式, 就是把byte中的二进制按无符号数解读了, 这样每个byte就可以正确表示ip地址中的值, 再把每个byte连接起来, 形成一个大的整数
        BigInteger start = new BigInteger(1, this.startAddress.getAddress());
        BigInteger end = new BigInteger(1, this.endAddress.getAddress());
        BigInteger target = new BigInteger(1, address.getAddress());

        int st = start.compareTo(target);
        int te = target.compareTo(end);

        return (st == -1 || st == 0) && (te == -1 || te == 0);
    }

    public static void main(String[] args) throws UnknownHostException {
        CIDRUtils cidrUtils = new CIDRUtils("192.168.1.0/26");
    }
}
