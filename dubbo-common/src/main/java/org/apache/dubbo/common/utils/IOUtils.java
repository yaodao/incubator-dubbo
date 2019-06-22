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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

public class IOUtils {
    private static final int BUFFER_SIZE = 1024 * 8;

    private IOUtils() {
    }

    /**
     * write.
     *
     * @param is InputStream instance.
     * @param os OutputStream instance.
     * @return count.
     * @throws IOException
     */
    // 将参数is中的数据 写入参数os, 一次写BUFFER_SIZE大小的数据
    // 简单理解说就是将一个文件中的内容写入另一个文件
    public static long write(InputStream is, OutputStream os) throws IOException {
        return write(is, os, BUFFER_SIZE);
    }

    /**
     * write.
     *
     * @param is         InputStream instance.
     * @param os         OutputStream instance.
     * @param bufferSize buffer size.
     * @return count.
     * @throws IOException
     */
    // 将参数is中的数据 写入参数os, 一次写bufferSize大小的数据
    public static long write(InputStream is, OutputStream os, int bufferSize) throws IOException {
        int read;
        long total = 0;
        byte[] buff = new byte[bufferSize];
        while (is.available() > 0) {// 适合读取本地文件
            read = is.read(buff, 0, buff.length);
            if (read > 0) {
                os.write(buff, 0, read);
                total += read;
            }
        }
        return total;
    }

    /**
     * read string.
     *
     * @param reader Reader instance.
     * @return String.
     * @throws IOException
     */
    public static String read(Reader reader) throws IOException {
        StringWriter writer = new StringWriter();
        try {
            write(reader, writer);
            return writer.getBuffer().toString();
        } finally {
            // StringWriter还真是需要关闭
            writer.close();
        }
    }

    /**
     * write string.
     *
     * @param writer Writer instance.
     * @param string String.
     * @throws IOException
     */
    public static long write(Writer writer, String string) throws IOException {
        Reader reader = new StringReader(string);
        try {
            return write(reader, writer);
        } finally {
            reader.close();
        }
    }

    /**
     * write.
     *
     * @param reader Reader.
     * @param writer Writer.
     * @return count.
     * @throws IOException
     */
    // 将参数is中的数据 写入参数os, 一次写bufferSize大小的数据
    public static long write(Reader reader, Writer writer) throws IOException {
        return write(reader, writer, BUFFER_SIZE);
    }

    /**
     * write.
     *
     * @param reader     Reader.
     * @param writer     Writer.
     * @param bufferSize buffer size.
     * @return count.
     * @throws IOException
     */
    // 将参数is中的数据 写入参数os, 一次写bufferSize大小的数据
    // 返回写入的总字符数(注意: 不是字节数)
    public static long write(Reader reader, Writer writer, int bufferSize) throws IOException {
        int read;
        long total = 0;
        char[] buf = new char[BUFFER_SIZE];
        while ((read = reader.read(buf)) != -1) {// 适合读取网络文件
            writer.write(buf, 0, read);
            total += read;
        }
        return total;
    }

    /**
     * read lines.
     *
     * @param file file.
     * @return lines.
     * @throws IOException
     */
    public static String[] readLines(File file) throws IOException {
        if (file == null || !file.exists() || !file.canRead()) {
            return new String[0];
        }

        return readLines(new FileInputStream(file));
    }

    /**
     * read lines.
     *
     * @param is input stream.
     * @return lines.
     * @throws IOException
     */
    // 将文件内容读出到数组, 返回内容数组
    public static String[] readLines(InputStream is) throws IOException {
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines.toArray(new String[0]);
        } finally {
            reader.close();
        }
    }

    /**
     * write lines.
     *
     * @param os    output stream.
     * @param lines lines.
     * @throws IOException
     */
    // 把lines数组按行写入文件
    public static void writeLines(OutputStream os, String[] lines) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(os));
        try {
            for (String line : lines) {
                writer.println(line);
            }
            writer.flush();
        } finally {
            writer.close();
        }
    }

    /**
     * write lines.
     *
     * @param file  file.
     * @param lines lines.
     * @throws IOException
     */
    // 适用于一次性写入所有数据, 再次调用该函数就会将之前写入的数据覆盖掉
    public static void writeLines(File file, String[] lines) throws IOException {
        if (file == null) {
            throw new IOException("File is null.");
        }
        writeLines(new FileOutputStream(file), lines);
    }

    /**
     * append lines.
     *
     * @param file  file.
     * @param lines lines.
     * @throws IOException
     */
    // 适用于分批的追加数据
    public static void appendLines(File file, String[] lines) throws IOException {
        if (file == null) {
            throw new IOException("File is null.");
        }
        writeLines(new FileOutputStream(file, true), lines);
    }

}