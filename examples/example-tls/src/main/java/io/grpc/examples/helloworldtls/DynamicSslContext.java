package io.grpc.examples.helloworldtls;

import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;

import io.grpc.netty.GrpcSslContexts;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

@SuppressWarnings("deprecation")
public class DynamicSslContext extends SslContext {
	
	private SslContext ctx;
	private final SslContextBuilder ctxBuilder;
	ScheduledExecutorService executor;


	public DynamicSslContext(SslContextBuilder builder, long period) throws SSLException {
	    this.ctx = builder.build();
	    this.ctxBuilder = builder;
	    executor = Executors.newSingleThreadScheduledExecutor();
	    TimerTask repeatedTask = new TimerTask() {
	        public void run() {
	        	try {
					ctx = GrpcSslContexts.configure(ctxBuilder).build();
				} catch (SSLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	        }
	    };
	    executor.scheduleAtFixedRate(repeatedTask, period, period, TimeUnit.MINUTES);
	}
	
	@Override
	public boolean isClient() {
		return ctx.isClient();
	}

	@Override
	public List<String> cipherSuites() {
		return ctx.cipherSuites();
	}

	@Override
	public long sessionCacheSize() {
		return ctx.sessionCacheSize();
	}

	@Override
	public long sessionTimeout() {
		return ctx.sessionTimeout();
	}

	@Override
	public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
		return ctx.applicationProtocolNegotiator();
	}

	@Override
	public SSLEngine newEngine(ByteBufAllocator alloc) {
		return ctx.newEngine(alloc);
	}

	@Override
	public SSLEngine newEngine(ByteBufAllocator alloc, String peerHost, int peerPort) {
		return ctx.newEngine(alloc, peerHost, peerPort);
	}

	@Override
	public SSLSessionContext sessionContext() {
		return ctx.sessionContext();
	}

}
