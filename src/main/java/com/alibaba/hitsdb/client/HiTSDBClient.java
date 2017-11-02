package com.alibaba.hitsdb.client;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.hitsdb.client.callback.QueryCallback;
import com.alibaba.hitsdb.client.callback.http.HttpResponseCallbackFactory;
import com.alibaba.hitsdb.client.consumer.Consumer;
import com.alibaba.hitsdb.client.consumer.ConsumerFactory;
import com.alibaba.hitsdb.client.exception.VIPClientException;
import com.alibaba.hitsdb.client.exception.http.HttpClientException;
import com.alibaba.hitsdb.client.exception.http.HttpClientInitException;
import com.alibaba.hitsdb.client.exception.http.HttpServerErrorException;
import com.alibaba.hitsdb.client.exception.http.HttpServerNotSupportException;
import com.alibaba.hitsdb.client.exception.http.HttpUnknowStatusException;
import com.alibaba.hitsdb.client.http.HttpAPI;
import com.alibaba.hitsdb.client.http.HttpClient;
import com.alibaba.hitsdb.client.http.HttpClientFactory;
import com.alibaba.hitsdb.client.http.response.HttpStatus;
import com.alibaba.hitsdb.client.http.response.ResultResponse;
import com.alibaba.hitsdb.client.queue.DataQueue;
import com.alibaba.hitsdb.client.queue.DataQueueFactory;
import com.alibaba.hitsdb.client.util.LinkedHashMapUtils;
import com.alibaba.hitsdb.client.value.JSONValue;
import com.alibaba.hitsdb.client.value.Result;
import com.alibaba.hitsdb.client.value.request.DumpMetaValue;
import com.alibaba.hitsdb.client.value.request.MetricTimeRange;
import com.alibaba.hitsdb.client.value.request.Point;
import com.alibaba.hitsdb.client.value.request.Query;
import com.alibaba.hitsdb.client.value.request.SubQuery;
import com.alibaba.hitsdb.client.value.request.SuggestValue;
import com.alibaba.hitsdb.client.value.request.TTLValue;
import com.alibaba.hitsdb.client.value.request.Timeline;
import com.alibaba.hitsdb.client.value.response.QueryResult;
import com.alibaba.hitsdb.client.value.response.TTLResult;
import com.alibaba.hitsdb.client.value.response.TagResult;
import com.alibaba.hitsdb.client.value.response.batch.DetailsResult;
import com.alibaba.hitsdb.client.value.response.batch.SummaryResult;
import com.alibaba.hitsdb.client.value.type.Suggest;

public class HiTSDBClient implements HiTSDB {
	private static final Logger LOGGER = LoggerFactory.getLogger(HiTSDBClient.class);
	private final DataQueue queue;
	private final Consumer consumer;
	private final HttpResponseCallbackFactory httpResponseCallbackFactory;
	private final boolean httpCompress;
	private final HttpClient httpclient;
	private final HiTSDBConfig config;

	public HiTSDBClient(HiTSDBConfig config) throws HttpClientInitException, VIPClientException {
		this.config = config;
		this.httpclient = HttpClientFactory.createHttpClient(config);
		this.httpCompress = config.isHttpCompress();
		boolean asyncPut = config.isAsyncPut();
		if (asyncPut) {
			this.httpResponseCallbackFactory = httpclient.getHttpResponseCallbackFactory();
			int batchPutBufferSize = config.getBatchPutBufferSize();
			int batchPutTimeLimit = config.getBatchPutTimeLimit();
			boolean backpressure = config.isBackpressure();
			this.queue = DataQueueFactory.createDataPointQueue(batchPutBufferSize, batchPutTimeLimit, backpressure);
			this.consumer = ConsumerFactory.createConsumer(queue, httpclient, config);
			this.consumer.start();
		} else {
			this.httpResponseCallbackFactory = null;
			this.queue = null;
			this.consumer = null;
		}

		this.httpclient.start();
		LOGGER.info("The hitsdb-client has started.");
	}

	@Override
	public void close() throws IOException {
		// 优雅关闭
		this.close(false);
	}

	/**
	 * 强制关闭
	 * 
	 * @throws Exception
	 */
	private void forceClose() throws IOException {
		boolean async = config.isAsyncPut();
		if (async) {
			// 消费者关闭
			this.consumer.stop(true);
		}

		// 客户端关闭
		this.httpclient.close(true);
	}

	/**
	 * 优雅关闭
	 * 
	 * @throws Exception
	 */
	private void gracefulClose() throws IOException {
		boolean async = config.isAsyncPut();

		if (async) {
			// 停止写入
			this.queue.forbiddenSend();

			// 等待队列消费为空
			this.queue.waitEmpty();

			// 消费者关闭
			this.consumer.stop();
		}

		// 客户端关闭
		this.httpclient.close();
	}

	@Override
	public void close(boolean force) throws IOException {
		if (force) {
			forceClose();
		} else {
			gracefulClose();
		}
		LOGGER.info("The hitsdb-client has closed.");
	}

	@Override
	public void deleteData(String metric, int startTime, int endTime) {
		MetricTimeRange metricTimeRange = new MetricTimeRange(metric, startTime, endTime);
		HttpResponse httpResponse = httpclient.post(HttpAPI.DELETE_DATA, metricTimeRange.toJSON());
		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.httpCompress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();
		switch (httpStatus) {
		case ServerSuccess:
			return;
		case ServerNotSupport:
			throw new HttpServerNotSupportException(resultResponse);
		case ServerError:
			throw new HttpServerErrorException(resultResponse);
		default:
			throw new HttpUnknowStatusException(resultResponse);
		}
	}

	@Override
	public void deleteData(String metric, Date startDate, Date endDate) {
		int startTime = (int) (startDate.getTime() / 1000);
		int endTime = (int) (startDate.getTime() / 1000);
		deleteData(metric, startTime, endTime);
	}

	@Override
	public void deleteMeta(String metric, Map<String, String> tags) {
		Timeline timeline = Timeline.metric(metric).tag(tags).build();
		deleteMeta(timeline);
	}

	@Override
	public void deleteMeta(Timeline timeline) {
		HttpResponse httpResponse = httpclient.post(HttpAPI.DELETE_META, timeline.toJSON());
		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.httpCompress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();
		switch (httpStatus) {
		case ServerSuccess:
			return;
		case ServerNotSupport:
			throw new HttpServerNotSupportException(resultResponse);
		case ServerError:
			throw new HttpServerErrorException(resultResponse);
		default:
			throw new HttpUnknowStatusException(resultResponse);
		}
	}

	@Override
	public List<TagResult> dumpMeta(String tagkey, String tagValuePrefix, int max) {
		DumpMetaValue dumpMetaValue = new DumpMetaValue(tagkey, tagValuePrefix, max);
		HttpResponse httpResponse = httpclient.post(HttpAPI.DUMP_META, dumpMetaValue.toJSON());
		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.httpCompress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();
		switch (httpStatus) {
		case ServerSuccess:
			String content = resultResponse.getContent();
			List<TagResult> tagResults = TagResult.parseList(content);
			return tagResults;
		case ServerNotSupport:
			throw new HttpServerNotSupportException(resultResponse);
		case ServerError:
			throw new HttpServerErrorException(resultResponse);
		default:
			throw new HttpUnknowStatusException(resultResponse);
		}

	}

	@Override
	public void put(Point point) {
		queue.send(point);
	}

	@Override
	public List<QueryResult> query(Query query) {
		boolean queryOne = false;
		int start = query.getStart();
		int end = query.getEnd();
		if (start == end) {
			start -= 1;
			queryOne = true;
			List<SubQuery> queries = query.getQueries();
			query = Query.timeRange(start, end).sub(queries).build();
		}

		HttpResponse httpResponse = httpclient.post(HttpAPI.QUERY, query.toJSON());
		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.httpCompress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();
		switch (httpStatus) {
		case ServerSuccessNoContent:
			return null;
		case ServerSuccess:
			String content = resultResponse.getContent();
			List<QueryResult> queryResultList;
			queryResultList = JSON.parseArray(content, QueryResult.class);
			if (queryOne) {
				for (QueryResult queryResult : queryResultList) {
					LinkedHashMap<Integer, Number> dps = queryResult.getDps();
					if (dps != null && !dps.isEmpty()) {
						Iterator<Entry<Integer, Number>> iterator = dps.entrySet().iterator();
						while (iterator.hasNext()) {
							Entry<Integer, Number> entry = iterator.next();
							Integer time = entry.getKey();
							if (!time.equals(end)) {
								iterator.remove();
							}
						}
					}

					LinkedHashMap<Integer, String> sdps = queryResult.getSdps();
					if (sdps != null && !sdps.isEmpty()) {
						Iterator<Entry<Integer, String>> iterator = sdps.entrySet().iterator();
						while (iterator.hasNext()) {
							Entry<Integer, String> entry = iterator.next();
							Integer time = entry.getKey();
							if (!time.equals(end)) {
								iterator.remove();
							}
						}
					}
				}
			}

			return queryResultList;
		case ServerNotSupport:
			throw new HttpServerNotSupportException(resultResponse);
		case ServerError:
			throw new HttpServerErrorException(resultResponse);
		default:
			throw new HttpUnknowStatusException(resultResponse);
		}

	}

	@Override
	public void query(Query query, QueryCallback callback) {
		FutureCallback<HttpResponse> httpCallback = null;
		String address = httpclient.getHttpAddressManager().getAddress();

		boolean queryOne = false;
		int start = query.getStart();
		int end = query.getEnd();
		if (start == end) {
			start -= 1;
			queryOne = true;
			List<SubQuery> queries = query.getQueries();
			query = Query.timeRange(start, end).sub(queries).build();
		}

		if (callback != null) {
			httpCallback = this.httpResponseCallbackFactory.createQueryCallback(address, callback, query, queryOne);
		}

		httpclient.postToAddress(address, HttpAPI.QUERY, query.toJSON(), httpCallback);
	}

	@Override
	public List<String> suggest(Suggest type, String prefix, int max) {
		SuggestValue suggestValue = new SuggestValue(type.getName(), prefix, max);
		HttpResponse httpResponse = httpclient.post(HttpAPI.SUGGEST, suggestValue.toJSON());
		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.httpCompress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();
		switch (httpStatus) {
		case ServerSuccess:
			String content = resultResponse.getContent();
			List<String> list = JSON.parseArray(content, String.class);
			return list;
		case ServerNotSupport:
			throw new HttpServerNotSupportException(resultResponse);
		case ServerError:
			throw new HttpServerErrorException(resultResponse);
		default:
			throw new HttpUnknowStatusException(resultResponse);
		}
	}

	@Override
	public int ttl() {
		HttpResponse httpResponse = httpclient.get(HttpAPI.TTL, null);
		ResultResponse result = ResultResponse.simplify(httpResponse, this.httpCompress);
		String content = result.getContent();
		TTLResult ttlResult = JSONValue.parseObject(content, TTLResult.class);
		return ttlResult.getVal();
	}

	@Override
	public void ttl(int lifetime) {
		TTLValue ttlValue = new TTLValue(lifetime);
		HttpResponse httpResponse = httpclient.post(HttpAPI.TTL, ttlValue.toJSON());
		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.httpCompress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();
		switch (httpStatus) {
		case ServerSuccess:
			return;
		case ServerNotSupport:
			throw new HttpServerNotSupportException(resultResponse);
		case ServerError:
			throw new HttpServerErrorException(resultResponse);
		default:
			throw new HttpUnknowStatusException(resultResponse);
		}
	}

	@Override
	public void ttl(int lifetime, TimeUnit unit) {
		int seconds = (int) unit.toSeconds(lifetime);
		TTLValue ttlValue = new TTLValue(seconds);
		HttpResponse httpResponse = httpclient.post(HttpAPI.TTL, ttlValue.toJSON());
		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.httpCompress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();
		switch (httpStatus) {
		case ServerSuccess:
			return;
		case ServerNotSupport:
			throw new HttpServerNotSupportException(resultResponse);
		case ServerError:
			throw new HttpServerErrorException(resultResponse);
		default:
			throw new HttpUnknowStatusException(resultResponse);
		}
	}

	@Override
	public List<QueryResult> last(Query query, int num) throws HttpUnknowStatusException {
		List<QueryResult> queryResults = this.query(query);
		for (QueryResult queryResult : queryResults) {
			{
				LinkedHashMap<Integer, Number> dps = queryResult.getDps();
				if (dps != null) {
					LinkedHashMap<Integer, Number> newDps = new LinkedHashMap<Integer, Number>(num);
					Entry<Integer, Number> lastEntry = LinkedHashMapUtils.getTail(dps);
					if (lastEntry != null) {
						newDps.put(lastEntry.getKey(), lastEntry.getValue());
						for (int count = 1; count < num; count++) {
							Entry<Integer, Number> beforeEntry = LinkedHashMapUtils.getBefore(lastEntry);
							if (beforeEntry != null) {
								newDps.put(beforeEntry.getKey(), beforeEntry.getValue());
								lastEntry = beforeEntry;
							} else {
								break;
							}
						}
					}

					queryResult.setDps(newDps);
				}
			}

			{
				LinkedHashMap<Integer, String> sdps = queryResult.getSdps();
				if (sdps != null) {
					LinkedHashMap<Integer, String> newDps = new LinkedHashMap<Integer, String>(num);
					Entry<Integer, String> lastEntry = LinkedHashMapUtils.getTail(sdps);
					if (lastEntry != null) {
						newDps.put(lastEntry.getKey(), lastEntry.getValue());
						for (int count = 1; count < num; count++) {
							Entry<Integer, String> beforeEntry = LinkedHashMapUtils.getBefore(lastEntry);
							if (beforeEntry != null) {
								newDps.put(beforeEntry.getKey(), beforeEntry.getValue());
								lastEntry = beforeEntry;
							} else {
								break;
							}
						}
					}

					queryResult.setSdps(sdps);
				}
			}
		}

		return queryResults;
	}

	@Override
	public Result putSync(Collection<Point> points) {
		return putSync(points, Result.class);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Result> T putSync(Collection<Point> points, Class<T> resultType) {
		String jsonString = JSON.toJSONString(points);

		HttpResponse httpResponse;
		if (resultType.equals(Result.class)) {
			httpResponse = httpclient.post(HttpAPI.PUT, jsonString);
		} else if (resultType.equals(SummaryResult.class)) {
			Map<String, String> paramsMap = new HashMap<String, String>();
			paramsMap.put("summary", "true");
			httpResponse = httpclient.post(HttpAPI.PUT, jsonString, paramsMap);
		} else if (resultType.equals(DetailsResult.class)) {
			Map<String, String> paramsMap = new HashMap<String, String>();
			paramsMap.put("details", "true");
			httpResponse = httpclient.post(HttpAPI.PUT, jsonString, paramsMap);
		} else {
			throw new HttpClientException("This result type is not supported");
		}

		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.httpCompress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();

		T result = null;
		switch (httpStatus) {
		case ServerSuccessNoContent:
			result = (T) new Result();
		case ServerSuccess:
			String content = resultResponse.getContent();
			if (resultType.equals(SummaryResult.class)) {
				result = (T) JSON.parseObject(content, SummaryResult.class);
			} else if (resultType.equals(DetailsResult.class)) {
				result = (T) JSON.parseObject(content, DetailsResult.class);
			}

			return result;
		case ServerNotSupport:
			throw new HttpServerNotSupportException(resultResponse);
		case ServerError:
			throw new HttpServerErrorException(resultResponse);
		default:
			throw new HttpUnknowStatusException(resultResponse);
		}
	}

}
