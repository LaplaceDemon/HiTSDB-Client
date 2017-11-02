package com.alibaba.hitsdb.client.callback.http;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;

import com.alibaba.fastjson.JSON;
import com.alibaba.hitsdb.client.callback.QueryCallback;
import com.alibaba.hitsdb.client.exception.http.HttpServerErrorException;
import com.alibaba.hitsdb.client.exception.http.HttpServerNotSupportException;
import com.alibaba.hitsdb.client.exception.http.HttpUnknowStatusException;
import com.alibaba.hitsdb.client.http.response.HttpStatus;
import com.alibaba.hitsdb.client.http.response.ResultResponse;
import com.alibaba.hitsdb.client.value.request.Query;
import com.alibaba.hitsdb.client.value.response.QueryResult;

public class QueryHttpResponseCallback implements FutureCallback<HttpResponse> {
	
	private final String address;
    private final Query query;
    private final QueryCallback callback;
    private final boolean compress;
    private final boolean queryOne;

    public QueryHttpResponseCallback(final String address, final Query query, QueryCallback callback,boolean compress,boolean queryOne) {
        super();
        this.address = address;
        this.query = query;
        this.callback = callback;
        this.compress = compress;
        this.queryOne = queryOne;
    }

    @Override
    public void completed(HttpResponse httpResponse) {
        ResultResponse resultResponse = ResultResponse.simplify(httpResponse,this.compress);
        HttpStatus httpStatus = resultResponse.getHttpStatus();
        switch (httpStatus) {
        case ServerSuccessNoContent:
        		callback.response(this.address, query, null);
            return;
        case ServerSuccess:
            String content = resultResponse.getContent();
            List<QueryResult> queryResultList = JSON.parseArray(content, QueryResult.class);
            
            if(queryOne) {
	            	int end = this.query.getEnd();
	        		for(QueryResult queryResult: queryResultList) {
	        			LinkedHashMap<Integer, Number> dps = queryResult.getDps();
	        			if(dps!=null && !dps.isEmpty()) {
	        				Iterator<Entry<Integer, Number>> iterator = dps.entrySet().iterator();
	        				while(iterator.hasNext()) {
	        					Entry<Integer, Number> entry = iterator.next();
	        					Integer time = entry.getKey();
	        					if(!time.equals(end)) {
	        						iterator.remove();
	        					}
	        				}
	        			}
	        			
	        			LinkedHashMap<Integer, String> sdps = queryResult.getSdps();
	        			if(sdps!=null && !sdps.isEmpty()) {
	        				Iterator<Entry<Integer, String>> iterator = sdps.entrySet().iterator();
	        				while(iterator.hasNext()) {
	        					Entry<Integer, String> entry = iterator.next();
	        					Integer time = entry.getKey();
	        					if(!time.equals(end)) {
	        						iterator.remove();
	        					}
	        				}
	        			}
	        		}
	        	}
            
            callback.response(this.address, query, queryResultList);
            return;
        case ServerNotSupport:
            callback.failed(this.address, query, new HttpServerNotSupportException(resultResponse));
        case ServerError:
            callback.failed(this.address, query, new HttpServerErrorException(resultResponse));
        default:
            callback.failed(this.address, query, new HttpUnknowStatusException(resultResponse));
        }
    }

    @Override
    public void failed(Exception ex) {
        callback.failed(this.address, query, ex);
    }

    @Override
    public void cancelled() {
    }

}
