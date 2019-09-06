/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * IP and Port Helper for RPC
 */
public class NetUtils {
    private static final Logger logger = LoggerFactory.getLogger(NetUtils.class);

    // returned port range is [30000, 39999]
    private static final int RND_PORT_START = 30000;
    private static final int RND_PORT_RANGE = 10000;

    // valid port range is (0, 65535] 这个是传输层的有效端口范围(tcp udp)
    private static final int MIN_PORT = 0;
    private static final int MAX_PORT = 65535;

    // ipv4地址+端口号 (端口号是1-5位)
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}\\:\\d{1,5}$");
    // 127开头的地址
    private static final Pattern LOCAL_IP_PATTERN = Pattern.compile("127(\\.\\d{1,3}){3}$");
    // 最多匹配6段用.分开的数字, 最少匹配4段用.分开的数字
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3,5}$");

    // key是ip地址, value是hostname
    private static final Map<String, String> hostNameCache = new LRUCache<>(1000);
    private static volatile InetAddress LOCAL_ADDRESS = null;

    private static final String SPLIT_IPV4_CHARECTER = "\\.";
    private static final String SPLIT_IPV6_CHARECTER = ":";

    // 随机返回一个[30000, 39999] 之间的整数
    public static int getRandomPort() {
        return RND_PORT_START + ThreadLocalRandom.current().nextInt(RND_PORT_RANGE);
    }

    // 创建一个socket对象，返回该socket正在侦听的端口号，
    // 不行则返回一个[30000, 39999] 之间的整数
    public static int getAvailablePort() {
        try (ServerSocket ss = new ServerSocket()) {
            // 默认绑定本机的ip
            ss.bind(null);
            // 返回ss正在监听的端口号
            return ss.getLocalPort();
        } catch (IOException e) {
            // 返回一个[30000, 39999] 之间的随机整数
            return getRandomPort();
        }
    }

    // 若入参port<=0， 则任意获取一个本机的端口号 或者 一个[30000, 39999] 之间的随机整数
    // 若入参port>0，则在参数 [port, MAX_PORT) 之间选一个有效的端口号（可连接上的端口号）
    public static int getAvailablePort(int port) {
        if (port <= 0) {
            // 若入参port<=0， 则获取一个本机的端口号 或者 一个[30000, 39999] 之间的随机整数
            return getAvailablePort();
        }

        // 在 [port，MAX_PORT)之间，测试出一个可以连接上的端口号
        for (int i = port; i < MAX_PORT; i++) {
            try (ServerSocket ss = new ServerSocket(i)) {
                return i;
            } catch (IOException e) {
                // continue
            }
        }
        return port;
    }

    // 判断入参port是否在有效范围
    public static boolean isInvalidPort(int port) {
        // port有效范围是 (0, 65535]
        return port <= MIN_PORT || port > MAX_PORT;
    }

    public static boolean isValidAddress(String address) {
        return ADDRESS_PATTERN.matcher(address).matches();
    }

    // 参数host是否是127开头的串, 或者是"localhost"
    public static boolean isLocalHost(String host) {
        return host != null
                // matches()需要host全部匹配pattern
                && (LOCAL_IP_PATTERN.matcher(host).matches()
                || host.equalsIgnoreCase(Constants.LOCALHOST_KEY));
    }

    public static boolean isAnyHost(String host) {
        return Constants.ANYHOST_VALUE.equals(host);
    }

    // FIXME: should remove this method completely
    public static boolean isInvalidLocalHost(String host) {
        return host == null
                || host.length() == 0
                || host.equalsIgnoreCase(Constants.LOCALHOST_KEY)
                || host.equals(Constants.ANYHOST_VALUE)
                || (LOCAL_IP_PATTERN.matcher(host).matches());
    }

    // FIXME: should remove this method completely
    public static boolean isValidLocalHost(String host) {
        return !isInvalidLocalHost(host);
    }

    public static InetSocketAddress getLocalSocketAddress(String host, int port) {
        return isInvalidLocalHost(host) ?
                new InetSocketAddress(port) : new InetSocketAddress(host, port);
    }

    // 参数ipv4地址是否有效
    static boolean isValidV4Address(InetAddress address) {
        String name = address.getHostAddress();
        return (name != null
                && IP_PATTERN.matcher(name).matches()
                && !Constants.ANYHOST_VALUE.equals(name)
                && !Constants.LOCALHOST_VALUE.equals(name));
    }

    /**
     * Check if an ipv6 address is reachable.
     *
     * @param address the given address
     * @return true if it is reachable
     */
    // 判断ipv6地址是否可达
    static boolean isValidV6Address(Inet6Address address) {
        boolean preferIpv6 = Boolean.getBoolean("java.net.preferIPv6Addresses");
        if (!preferIpv6) {
            return false;
        }
        try {
            // 判定节点是否可达, 注意指定时间内没有响应, 也被认为不可达
            return address.isReachable(100);
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    // 判断参数地址是不是外网ip, true是外网ip
    static boolean isValidPublicAddress(InetAddress address) {
        // isSiteLocalAddress判断address是否是内网ip, isLoopbackAddress判断address是否以127开头的ip
        return !address.isSiteLocalAddress() && !address.isLoopbackAddress();
    }

    /**
     * normalize the ipv6 Address, convert scope name to scope id.
     * e.g.
     * convert
     * fe80:0:0:0:894:aeec:f37d:23e1%en0
     * to
     * fe80:0:0:0:894:aeec:f37d:23e1%5
     * <p>
     * The %5 after ipv6 address is called scope id.
     * see java doc of {@link Inet6Address} for more details.
     *
     * @param address the input address
     * @return the normalized address, with scope id converted to int
     */
    static InetAddress normalizeV6Address(Inet6Address address) {
        String addr = address.getHostAddress();
        int i = addr.lastIndexOf('%');
        if (i > 0) {
            try {
                return InetAddress.getByName(addr.substring(0, i) + '%' + address.getScopeId());
            } catch (UnknownHostException e) {
                // ignore
                logger.debug("Unknown IPV6 address: ", e);
            }
        }
        return address;
    }

    // 获取本机的ip地址
    public static String getLocalHost() {
        InetAddress address = getLocalAddress();
        return address == null ? Constants.LOCALHOST_VALUE : address.getHostAddress();
    }

    public static String filterLocalHost(String host) {
        if (host == null || host.length() == 0) {
            return host;
        }
        if (host.contains("://")) {
            URL u = URL.valueOf(host);
            if (NetUtils.isInvalidLocalHost(u.getHost())) {
                return u.setHost(NetUtils.getLocalHost()).toFullString();
            }
        } else if (host.contains(":")) {
            int i = host.lastIndexOf(':');
            if (NetUtils.isInvalidLocalHost(host.substring(0, i))) {
                return NetUtils.getLocalHost() + host.substring(i);
            }
        } else {
            if (NetUtils.isInvalidLocalHost(host)) {
                return NetUtils.getLocalHost();
            }
        }
        return host;
    }

    public static String getIpByConfig() {
        String configIp = ConfigurationUtils.getProperty(Constants.DUBBO_IP_TO_BIND);
        if (configIp != null) {
            return configIp;
        }

        return getIpByHost(getLocalAddress().getHostName());
    }

    /**
     * Find first valid IP from local network card
     * 返回本地的ip地址
     * @return first valid local IP
     */
    public static InetAddress getLocalAddress() {
        if (LOCAL_ADDRESS != null) {
            return LOCAL_ADDRESS;
        }
        // 获取本机的ip地址
        InetAddress localAddress = getLocalAddress0();
        LOCAL_ADDRESS = localAddress;
        return localAddress;
    }

    // 验证参数address是否是有效的ip地址
    private static Optional<InetAddress> toValidAddress(InetAddress address) {
        if (isValidPublicAddress(address)) {
            if (address instanceof Inet6Address) {
                Inet6Address v6Address = (Inet6Address) address;
                if (isValidV6Address(v6Address)) {
                    return Optional.ofNullable(normalizeV6Address(v6Address));
                }
            }
            if (isValidV4Address(address)) {
                return Optional.of(address);
            }
        }
        return Optional.empty();
    }

    // 获取本机的ip地址 (ipv4/ipv6)
    private static InetAddress getLocalAddress0() {
        InetAddress localAddress = null;
        try {
            // 先使用DNS, 从hostname获取ip地址
            localAddress = InetAddress.getLocalHost();
            Optional<InetAddress> addressOp = toValidAddress(localAddress);
            if (addressOp.isPresent()) {
                return addressOp.get();
            }
        } catch (Throwable e) {
            logger.warn(e);
        }

        try {
            // 再获取本机的所有网卡信息, 从中过滤一条ip地址
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (null == interfaces) {
                return localAddress;
            }
            while (interfaces.hasMoreElements()) {
                try {
                    NetworkInterface network = interfaces.nextElement();
                    Enumeration<InetAddress> addresses = network.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        try {
                            Optional<InetAddress> addressOp = toValidAddress(addresses.nextElement());
                            if (addressOp.isPresent()) {
                                return addressOp.get();
                            }
                        } catch (Throwable e) {
                            logger.warn(e);
                        }
                    }
                } catch (Throwable e) {
                    logger.warn(e);
                }
            }
        } catch (Throwable e) {
            logger.warn(e);
        }
        return localAddress;
    }

    /**
     * 从ip地址串得到hostname, 若跟hostNameCache存的不同则更新hostNameCache中的值
     * @param address ip地址串
     * @return
     */
    public static String getHostName(String address) {
        try {
            int i = address.indexOf(':');
            if (i > -1) {
                // 去掉端口号
                address = address.substring(0, i);
            }
            String hostname = hostNameCache.get(address);
            if (hostname != null && hostname.length() > 0) {
                return hostname;
            }
            InetAddress inetAddress = InetAddress.getByName(address);
            if (inetAddress != null) {
                hostname = inetAddress.getHostName();
                hostNameCache.put(address, hostname);
                return hostname;
            }
        } catch (Throwable e) {
            // ignore
        }
        return address;
    }

    /**
     * @param hostName
     * @return ip address or hostName if UnknownHostException
     */
    // 通过hostname得到对应的ip
    public static String getIpByHost(String hostName) {
        try {
            return InetAddress.getByName(hostName).getHostAddress();
        } catch (UnknownHostException e) {
            return hostName;
        }
    }

    // 得到字符串 "ip:port"
    public static String toAddressString(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    // 构造一个InetSocketAddress对象--含有ip和端口
    public static InetSocketAddress toAddress(String address) {
        int i = address.indexOf(':');
        String host;
        int port;
        if (i > -1) {
            // 分解成ip和端口
            host = address.substring(0, i);
            port = Integer.parseInt(address.substring(i + 1));
        } else {
            host = address;
            port = 0;
        }
        // InetSocketAddress在BIO常用的例子里就有, 就是一个由地址和端口做参数构造的对象
        return new InetSocketAddress(host, port);
    }

    // 得到字符串 "protocol://host:port/path"
    public static String toURL(String protocol, String host, int port, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append(protocol).append("://");
        sb.append(host).append(':').append(port);
        if (path.charAt(0) != '/') {
            sb.append('/');
        }
        sb.append(path);
        return sb.toString();
    }

    public static void joinMulticastGroup(MulticastSocket multicastSocket, InetAddress multicastAddress) throws IOException {
        setInterface(multicastSocket, multicastAddress instanceof Inet6Address);
        multicastSocket.setLoopbackMode(false);
        multicastSocket.joinGroup(multicastAddress);
    }

    public static void setInterface(MulticastSocket multicastSocket, boolean preferIpv6) throws IOException {
        boolean interfaceSet = false;
        Enumeration interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface i = (NetworkInterface) interfaces.nextElement();
            Enumeration addresses = i.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = (InetAddress) addresses.nextElement();
                if (preferIpv6 && address instanceof Inet6Address) {
                    multicastSocket.setInterface(address);
                    interfaceSet = true;
                    break;
                } else if (!preferIpv6 && address instanceof Inet4Address) {
                    multicastSocket.setInterface(address);
                    interfaceSet = true;
                    break;
                }
            }
            if (interfaceSet) {
                break;
            }
        }
    }

    // 判断host是否在pattern范围内(就是ip地址是否在patter的地址范围, host可以是域名可以是ip地址, 在函数内部会把域名转为ip地址)
    public static boolean matchIpExpression(String pattern, String host, int port) throws UnknownHostException {

        // if the pattern is subnet format, it will not be allowed to config port param in pattern.
        // 若参数pattern是cider地址, 则判断host是否在cider的地址范围内
        if (pattern.contains("/")) {
            CIDRUtils utils = new CIDRUtils(pattern);
            return utils.isInRange(host);
        }


        return matchIpRange(pattern, host, port);
    }

    /**
     * 查看参数host(ip地址)是否在pattern指定的范围内
     * @param pattern 一个ip地址范围, 例如: "192.168.1.1-63" , "234e:0:4567:0:0:0:3d:0-ee"
     * @param host 要查看的ip地址
     * @param port
     * @return
     * @throws UnknownHostException
     */
    public static boolean matchIpRange(String pattern, String host, int port) throws UnknownHostException {
        if (pattern == null || host == null) {
            throw new IllegalArgumentException("Illegal Argument pattern or hostName. Pattern:" + pattern + ", Host:" + host);
        }
        pattern = pattern.trim();
        // 这里定义pattern="*.*.*.*" 可以表示所有的地址范围
        if (pattern.equals("*.*.*.*") || pattern.equals("*")) {
            return true;
        }

        InetAddress inetAddress = InetAddress.getByName(host);
        boolean isIpv4 = isValidV4Address(inetAddress) ? true : false;
        String[] hostAndPort = getPatternHostAndPort(pattern, isIpv4);
        // 如果pattern里面有port, 就比较下port是否相同, 不同直接返回false, 没有就不比较
        if (hostAndPort[1] != null && !hostAndPort[1].equals(String.valueOf(port))) {
            return false;
        }
        pattern = hostAndPort[0];

        String splitCharacter = SPLIT_IPV4_CHARECTER;
        if (!isIpv4) {
            splitCharacter = SPLIT_IPV6_CHARECTER;
        }
        // pattern分成数组
        String[] mask = pattern.split(splitCharacter);
        //check format of pattern
        checkHostPattern(pattern, mask, isIpv4);

        // 得到ip地址
        host = inetAddress.getHostAddress();
        // ip地址分成数组
        String[] ip_address = host.split(splitCharacter);
        if (pattern.equals(host)) {
            return true;
        }
        // short name condition
        // pattern是固定的某个地址, 而不是一个地址范围
        if (!ipPatternContainExpression(pattern)) {
            InetAddress patternAddress = InetAddress.getByName(pattern);
            if (patternAddress.getHostAddress().equals(host)) {
                return true;
            } else {
                return false;
            }
        }
        // 挨个元素的比较pattern和host, 看host是否在pattern内
        for (int i = 0; i < mask.length; i++) {
            if (mask[i].equals("*") || mask[i].equals(ip_address[i])) {
                continue;
            } else if (mask[i].contains("-")) {
                String[] rangeNumStrs = mask[i].split("-");
                if (rangeNumStrs.length != 2) {
                    throw new IllegalArgumentException("There is wrong format of ip Address: " + mask[i]);
                }
                Integer min = getNumOfIpSegment(rangeNumStrs[0], isIpv4);
                Integer max = getNumOfIpSegment(rangeNumStrs[1], isIpv4);
                Integer ip = getNumOfIpSegment(ip_address[i], isIpv4);
                if (ip < min || ip > max) {
                    return false;
                }
            } else if ("0".equals(ip_address[i]) && ("0".equals(mask[i]) || "00".equals(mask[i]) || "000".equals(mask[i]) || "0000".equals(mask[i]))) {
                continue;
            } else if (!mask[i].equals(ip_address[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean ipPatternContainExpression(String pattern) {
        return pattern.contains("*") || pattern.contains("-");
    }

    private static void checkHostPattern(String pattern, String[] mask, boolean isIpv4) {
        if (!isIpv4) {
            if (mask.length != 8 && ipPatternContainExpression(pattern)) {
                throw new IllegalArgumentException("If you config ip expression that contains '*' or '-', please fill qulified ip pattern like 234e:0:4567:0:0:0:3d:*. ");
            }
            if (mask.length != 8 && !pattern.contains("::")) {
                throw new IllegalArgumentException("The host is ipv6, but the pattern is not ipv6 pattern : " + pattern);
            }
        } else {
            if (mask.length != 4) {
                throw new IllegalArgumentException("The host is ipv4, but the pattern is not ipv4 pattern : " + pattern);
            }
        }
    }

    // 获取IP地址的pattern 和端口号 其中, ip地址的pattern类似 "192.168.1.63-65:90" 或者 "[234e:0:4567:0:0:0:3d:0-ee]:8090"
    private static String[] getPatternHostAndPort(String pattern, boolean isIpv4) {
        String[] result = new String[2];
        if (pattern.startsWith("[") && pattern.contains("]:")) {
            int end = pattern.indexOf("]:");
            result[0] = pattern.substring(1, end);
            result[1] = pattern.substring(end + 2);
            return result;
        } else if (pattern.startsWith("[") && pattern.endsWith("]")) {
            result[0] = pattern.substring(1, pattern.length() - 1);
            result[1] = null;
            return result;
        } else if (isIpv4 && pattern.contains(":")) {
            int end = pattern.indexOf(":");
            result[0] = pattern.substring(0, end);
            result[1] = pattern.substring(end + 1);
            return result;
        } else {
            result[0] = pattern;
            return result;
        }
    }

    // 将ip地址的单个部分解析成整数
    private static Integer getNumOfIpSegment(String ipSegment, boolean isIpv4) {
        if (isIpv4) {
            return Integer.parseInt(ipSegment);
        }
        // ipSegment是16进制表示, 将ipSegment解析成一个十进制整数
        return Integer.parseInt(ipSegment, 16);
    }

}
