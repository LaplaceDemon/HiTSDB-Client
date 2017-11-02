package com.alibaba.hitsdb.client.callback.http;

import java.net.SocketTimeoutException;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.hitsdb.client.HiTSDBConfig;
import com.alibaba.hitsdb.client.callback.AbstractBatchPutCallback;
import com.alibaba.hitsdb.client.callback.BatchPutCallback;
import com.alibaba.hitsdb.client.callback.BatchPutDetailsCallback;
import com.alibaba.hitsdb.client.callback.BatchPutSummaryCallback;
import com.alibaba.hitsdb.client.exception.http.HttpClientConnectionRefusedException;
import com.alibaba.hitsdb.client.exception.http.HttpClientSocketTimeoutException;
import com.alibaba.hitsdb.client.exception.http.HttpServerErrorException;
import com.alibaba.hitsdb.client.exception.http.HttpServerNotSupportException;
import com.alibaba.hitsdb.client.exception.http.HttpUnknowStatusException;
import com.alibaba.hitsdb.client.http.HttpAPI;
import com.alibaba.hitsdb.client.http.HttpAddressManager;
import com.alibaba.hitsdb.client.http.HttpClient;
import com.alibaba.hitsdb.client.http.response.HttpStatus;
import com.alibaba.hitsdb.client.http.response.ResultResponse;
import com.alibaba.hitsdb.client.value.Result;
import com.alibaba.hitsdb.client.value.request.Point;
import com.alibaba.hitsdb.client.value.response.batch.DetailsResult;
import com.alibaba.hitsdb.client.value.response.batch.SummaryResult;

public class BatchPutHttpResponseCallback implements FutureCallback<HttpResponse> {
	private static final Logger LOGGER = LoggerFactory.getLogger(BatchPutHttpResponseCallback.class);

	private final AbstractBatchPutCallback<?> batchPutCallback;
	private final List<Point> pointList;
	private final int batchPutRetryTimes;
	private final boolean compress;
	private final HttpClient hitsdbHttpClient;
	private final HiTSDBConfig config;
	private final String address;

	public BatchPutHttpResponseCallback(String address,HttpClient httpclient, AbstractBatchPutCallback<?> batchPutCallback,List<Point> pointList, HiTSDBConfig config, int batchPutRetryTimes) {
		super();
		this.address = address;
		this.hitsdbHttpClient = httpclient;
		this.batchPutCallback = batchPutCallback;
		this.pointList = pointList;
		this.batchPutRetryTimes = batchPutRetryTimes;
		this.compress = config.isHttpCompress();
		this.config = config;
	}

	@Override
	public void completed(HttpResponse httpResponse) {
		// 处理响应
		ResultResponse resultResponse = ResultResponse.simplify(httpResponse, this.compress);
		HttpStatus httpStatus = resultResponse.getHttpStatus();
		switch (httpStatus) {
			case ServerSuccess:
			case ServerSuccessNoContent:
				// 正常释放Semaphor
				this.hitsdbHttpClient.getSemaphoreManager().release(address);
				
				if (batchPutCallback == null) {
					return;
				}

				if (batchPutCallback instanceof BatchPutCallback) {
					((BatchPutCallback) batchPutCallback).response(this.address, pointList, new Result());
					return;
				} else if (batchPutCallback instanceof BatchPutSummaryCallback) {
					SummaryResult summaryResult = null;
					if (!httpStatus.equals(HttpStatus.ServerSuccessNoContent)) {
						String content = resultResponse.getContent();
						summaryResult = JSON.parseObject(content, SummaryResult.class);
					}
					((BatchPutSummaryCallback) batchPutCallback).response(this.address, pointList, summaryResult);
					return;
				} else if (batchPutCallback instanceof BatchPutDetailsCallback) {
					DetailsResult detailsResult = null;
					if (!httpStatus.equals(HttpStatus.ServerSuccessNoContent)) {
						String content = resultResponse.getContent();
						detailsResult = JSON.parseObject(content, DetailsResult.class);
					}
	
					((BatchPutDetailsCallback) batchPutCallback).response(this.address,pointList, detailsResult);
					return;
				}
			case ServerNotSupport: {
				// 服务器返回4xx错误
				// 正常释放Semaphor
				this.hitsdbHttpClient.getSemaphoreManager().release(address);
				HttpServerNotSupportException ex = new HttpServerNotSupportException(resultResponse);
				this.failedWithResponse(ex);
				return;
			}
			case ServerError: {
				if (this.batchPutRetryTimes == 0) {
					// 服务器返回5xx错误
					// 正常释放Semaphor
					this.hitsdbHttpClient.getSemaphoreManager().release(address);
					HttpServerErrorException ex = new HttpServerErrorException(resultResponse);
					this.failedWithResponse(ex);
				} else {
					errorRetry();
				}
	
				return;
			}
			default: {
				HttpUnknowStatusException ex = new HttpUnknowStatusException(resultResponse);
				this.failedWithResponse(ex);
				return;
			}
		}
	}

	/**
	 * 有响应的异常处理。
	 * @param ex
	 */
	private void failedWithResponse(Exception ex) {
		if (batchPutCallback == null) { // 无回调逻辑，则失败打印日志。
			LOGGER.error("No callback logic exception. address:" + this.address, ex);
		} else {
			batchPutCallback.failed(this.address, pointList, ex);
		}
	}

	private String getNextAddress() {
		HttpAddressManager httpAddressManager = hitsdbHttpClient.getHttpAddressManager();
		String newAddress = httpAddressManager.getAddress();
		return newAddress;
	}
	
	private void errorRetry() {
		String newAddress;
		boolean acquire;
		int retryTimes = this.batchPutRetryTimes;
		while(true) {
			newAddress = getNextAddress();
			acquire = this.hitsdbHttpClient.getSemaphoreManager().acquire(newAddress);
			retryTimes--;
			if(acquire || retryTimes <= 0) {
				break;
			}
		}
		
		if(retryTimes == 0) {
			this.hitsdbHttpClient.getSemaphoreManager().release(address);
			return ;
		}
		
		// retry!
		LOGGER.warn("retry put data!");
		HttpResponseCallbackFactory httpResponseCallbackFactory = this.hitsdbHttpClient.getHttpResponseCallbackFactory();
		
		FutureCallback<HttpResponse> retryCallback;
		if (batchPutCallback != null) {
			retryCallback = httpResponseCallbackFactory.createBatchPutDataCallback(newAddress,this.batchPutCallback,this.pointList, this.config, retryTimes);
		} else {
			retryCallback = httpResponseCallbackFactory.createNoLogicBatchPutHttpFutureCallback(newAddress,this.pointList,this.config, retryTimes);
		}

		String jsonString = JSON.toJSONString(pointList);
		this.hitsdbHttpClient.post(HttpAPI.PUT, jsonString, retryCallback);
	}

	@Override
	public void failed(Exception ex) {
		// 异常重试
		if (ex instanceof SocketTimeoutException) {
			if (this.batchPutRetryTimes == 0) {
				ex = new HttpClientSocketTimeoutException(ex);
			} else {
				errorRetry();
				return;
			}
		} else if (ex instanceof java.net.ConnectException) {
			if (this.batchPutRetryTimes == 0) {
				ex = new HttpClientConnectionRefusedException(this.address,ex);
			} else {
				errorRetry();
				return;
			}
		}
		
		// 重试后释放semaphore许可
		this.hitsdbHttpClient.getSemaphoreManager().release(address);

		// 处理完毕，向逻辑层传递异常并处理。
		if (batchPutCallback == null) {
			LOGGER.error("No callback logic exception.", ex);
			return;
		} else {
			batchPutCallback.failed(this.address, pointList, ex);
		}
		
	}

	@Override
	public void cancelled() {
		this.hitsdbHttpClient.getSemaphoreManager().release(this.address);
		LOGGER.info("the HttpAsyncClient has been cancelled");
	}

}