//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.servlets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadStarvationTest
{
    private Server _server;

    @AfterEach
    public void dispose() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    @Tag("flaky")
    public void testDefaultServletSuccess() throws Exception
    {
        int maxThreads = 6;
        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, maxThreads);
        threadPool.setDetailedDump(true);
        _server = new Server(threadPool);

        // Prepare a big file to download.
        File directory = MavenTestingUtils.getTargetTestingDir();
        Files.createDirectories(directory.toPath());
        String resourceName = "resource.bin";
        Path resourcePath = Paths.get(directory.getPath(), resourceName);
        try (OutputStream output = Files.newOutputStream(resourcePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE))
        {
            byte[] chunk = new byte[256 * 1024];
            Arrays.fill(chunk, (byte)'X');
            chunk[chunk.length - 2] = '\r';
            chunk[chunk.length - 1] = '\n';
            for (int i = 0; i < 1024; ++i)
            {
                output.write(chunk);
            }
        }

        CountDownLatch writePending = new CountDownLatch(1);
        ServerConnector connector = new ServerConnector(_server, 0, 1)
        {
            @Override
            protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
            {
                return new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                {
                    @Override
                    protected void onIncompleteFlush()
                    {
                        super.onIncompleteFlush();
                        writePending.countDown();
                    }
                };
            }
        };
        connector.setIdleTimeout(Long.MAX_VALUE);
        _server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(_server, "/");
        context.setResourceBase(directory.toURI().toString());
        context.addServlet(DefaultServlet.class, "/*").setAsyncSupported(false);
        _server.setHandler(context);

        _server.start();

        List<Socket> sockets = new ArrayList<>();
        for (int i = 0; i < maxThreads * 2; ++i)
        {
            Socket socket = new Socket("localhost", connector.getLocalPort());
            sockets.add(socket);
            OutputStream output = socket.getOutputStream();
            String request =
                "GET /" + resourceName + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        // Wait for a thread on the servlet to block.
        assertTrue(writePending.await(5, TimeUnit.SECONDS));

        ExecutorService executor = Executors.newCachedThreadPool();

        long expected = Files.size(resourcePath);
        List<CompletableFuture<Integer>> totals = new ArrayList<>();
        for (Socket socket : sockets)
        {
            InputStream input = socket.getInputStream();
            totals.add(CompletableFuture.supplyAsync(() ->
            {
                try
                {
                    HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(input));
                    if (response != null)
                        return response.getContentBytes().length;
                    return 0;
                }
                catch (IOException x)
                {
                    x.printStackTrace();
                    return -1;
                }
            }, executor));
        }

        // Wait for all responses to arrive.
        CompletableFuture.allOf(totals.toArray(new CompletableFuture[0])).get(20, TimeUnit.SECONDS);

        for (CompletableFuture<Integer> total : totals)
        {
            assertFalse(total.isCompletedExceptionally());
            assertEquals(expected, total.get().intValue());
        }

        // We could read everything, good.
        for (Socket socket : sockets)
        {
            socket.close();
        }

        executor.shutdown();

        _server.stop();
    }

    @Test
    @Tag("flaky")
    public void testFailureStarvation() throws Exception
    {
        Logger serverInternalLogger = LoggerFactory.getLogger("org.eclipse.jetty.server.internal");
        try (StacklessLogging ignored = new StacklessLogging(serverInternalLogger))
        {
            int acceptors = 0;
            int selectors = 1;
            int maxThreads = 10;
            int parties = maxThreads - acceptors - selectors * 2;
            CyclicBarrier barrier = new CyclicBarrier(parties);

            QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, maxThreads);
            threadPool.setDetailedDump(true);
            _server = new Server(threadPool);

            ServerConnector connector = new ServerConnector(_server, acceptors, selectors)
            {
                @Override
                protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
                {
                    return new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                    {
                        @Override
                        public boolean flush(ByteBuffer... buffers) throws IOException
                        {
                            // Write only the headers, then throw.
                            super.flush(buffers[0]);
                            throw new IOException("thrown by test");
                        }
                    };
                }
            };
            connector.setIdleTimeout(Long.MAX_VALUE);
            _server.addConnector(connector);

            AtomicInteger count = new AtomicInteger(0);
            _server.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception
                {
                    int c = count.getAndIncrement();
                    if (c < parties)
                        barrier.await(10, TimeUnit.SECONDS);
                    response.setStatus(200);
                    response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 13);
                    Content.Sink.write(response, true, "Hello World!\n", callback);
                    return true;
                }
            });

            _server.start();

            List<Socket> sockets = new ArrayList<>();
            for (int i = 0; i < maxThreads * 2; ++i)
            {
                Socket socket = new Socket("localhost", connector.getLocalPort());
                sockets.add(socket);
                OutputStream output = socket.getOutputStream();
                String request = """
                    GET / HTTP/1.1\r
                    Host: localhost\r
                    \r
                    """;
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }

            ExecutorService executor = Executors.newCachedThreadPool();

            List<CompletableFuture<Integer>> totals = new ArrayList<>();
            for (Socket socket : sockets)
            {
                InputStream input = socket.getInputStream();
                totals.add(CompletableFuture.supplyAsync(() ->
                {
                    try
                    {
                        HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(input));
                        if (response != null)
                            return response.getContentBytes().length;
                        return input.read();
                    }
                    catch (Exception x)
                    {
                        x.printStackTrace();
                        return -1;
                    }
                }, executor));
            }

            // Wait for all responses to arrive.
            CompletableFuture.allOf(totals.toArray(new CompletableFuture[0])).get(20, TimeUnit.SECONDS);

            for (CompletableFuture<Integer> total : totals)
            {
                assertFalse(total.isCompletedExceptionally());
                assertEquals(-1, total.get().intValue());
            }

            // We could read everything, good.
            for (Socket socket : sockets)
            {
                socket.close();
            }

            executor.shutdown();

            _server.stop();
        }
    }
}
