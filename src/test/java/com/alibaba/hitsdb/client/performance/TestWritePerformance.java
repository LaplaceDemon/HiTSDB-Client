package com.alibaba.hitsdb.client.performance;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.alibaba.hitsdb.client.HiTSDB;
import com.alibaba.hitsdb.client.HiTSDBClientFactory;
import com.alibaba.hitsdb.client.HiTSDBConfig;
import com.alibaba.hitsdb.client.callback.BatchPutCallback;
import com.alibaba.hitsdb.client.exception.VIPClientException;
import com.alibaba.hitsdb.client.exception.http.HttpClientInitException;
import com.alibaba.hitsdb.client.value.Result;
import com.alibaba.hitsdb.client.value.request.Point;

//@Ignore
public class TestWritePerformance {

	HiTSDB tsdb;
	final AtomicLong t0 = new AtomicLong();
	final AtomicLong t1 = new AtomicLong();

	int P_NUM = 1;
	final int SIZE = 40000000;
	final AtomicLong num = new AtomicLong();

	@Before
	public void init() throws IOException, VIPClientException, HttpClientInitException {
		System.out.println("按下任意键，开始运行...");
		while (true) {
			int read = System.in.read();
			if (read != 0) {
				break;
			}
		}
		System.out.println("开始运行");

		BatchPutCallback cb = new BatchPutCallback() {

			@Override
			public void response(String address, List<Point> input, Result output) {
				long afterNum = num.addAndGet(input.size());
				System.out.println("成功处理" + input.size() + ",已处理" + afterNum);
			}

			@Override
			public void failed(String address, List<Point> input, Exception ex) {
				System.err.print(address + ":" + ex);
				long afterNum = num.addAndGet(input.size());
				System.out.println("失败处理" + input.size() + ",已处理" + afterNum);
			}
		};

		HiTSDBConfig.Builder.ProducerThreadSerializeSwitch = true;

		HiTSDBConfig config = HiTSDBConfig.address("hitsdb2.dbpaas.com", 8242).httpConnectionPool(512)
				.batchPutBufferSize(1000000).batchPutSize(500)
				 .listenBatchPut(cb)
				.ioThreadCount(2).batchPutConsumerThreadCount(2)
				.config();

		tsdb = HiTSDBClientFactory.connect(config);
	}

	@After
	public void end() throws IOException {
		// 优雅关闭
		System.err.println("关闭客户端！！！");
		tsdb.close();
		t1.compareAndSet(0, System.currentTimeMillis());

		double dt = t1.get() - t0.get();
		System.out.println("处理：" + num);
		System.out.println("时间：" + (dt));
		System.out.println("消耗速率" + SIZE * P_NUM / dt + "K/s");
		System.out.println("结束");
	}

	// public Point createPoint(int tag, double value) {
	// int t = (int) (System.currentTimeMillis() / 1000);
	// return Point.metric("test-performance").tag("tag",
	// String.valueOf(tag)).value(t, value).build();
	// }

	@Test
	public void testBatchPut() throws InterruptedException {
		final CountDownLatch countDownLatch = new CountDownLatch(P_NUM);
		final long currentTimeMillis = System.currentTimeMillis();
		long version = System.currentTimeMillis();
		final Point point = Point.metric("test-performance-hitsdb").tag("tag", "v1.0")
				.value((int) (currentTimeMillis / 1000), 54321.12345).version(version).build();

		Thread[] p_threads = new Thread[P_NUM];
		for (int x = 0; x < p_threads.length; x++) {
			final int index = x;
			p_threads[index] = new Thread(new Runnable() {
				// final Random random = new Random();

				@Override
				public void run() {
					t0.compareAndSet(0, currentTimeMillis);
					for (int i = 0; i < SIZE; i++) {
						// double nextDouble = random.nextDouble();
						// double nextDouble = 321.123456;
						// Point point = createPoint(index, nextDouble);
						try {
							// System.out.println(point.toJSON());
							tsdb.put(point);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}

					// 该线程发送完
					countDownLatch.countDown();
					System.out.println("线程写入结束！");
				}
			});
		}

		// start
		for (int x = 0; x < p_threads.length; x++) {
			p_threads[x].start();
		}

		// 发送线程发送完毕
		countDownLatch.await();
		System.out.println("主线程将要结束，尝试优雅关闭");
	}
}