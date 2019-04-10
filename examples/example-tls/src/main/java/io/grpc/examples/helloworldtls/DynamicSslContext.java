package io.grpc.examples.helloworldtls;

import java.io.File;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;

import io.grpc.netty.GrpcSslContexts;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

@SuppressWarnings("deprecation")
public class DynamicSslContext extends SslContext {
    private static final Logger logger = Logger.getLogger(DynamicSslContext.class.getName());
	
	private SslContext ctx;
	private SslContextBuilder ctxBuilder;
	ScheduledExecutorService executor;
    private final File certChainFilePath;
    private final File privateKeyFilePath;
    private final File trustCertCollectionFilePath;
	
	/**
	 * 
	 * 
	 * 
	 * @param builder  Make sure to pass configured builder e.g. 
	 *     GrpcSslContexts.configure(sslClientContextBuilder, SslProvider.OPENSSL)
	 * @param period
	 * @throws SSLException
	 */
	public DynamicSslContext(final File certChainFilePath, final File privateKeyFilePath,
			final File trustCertCollectionFilePath, long period) throws SSLException {
        this.certChainFilePath = certChainFilePath;
        this.privateKeyFilePath = privateKeyFilePath;
        this.trustCertCollectionFilePath = trustCertCollectionFilePath;
        createContextBuilder(certChainFilePath, privateKeyFilePath,
    			trustCertCollectionFilePath, true);
		if (period > 0L) {
			executor = Executors.newSingleThreadScheduledExecutor();
			TimerTask repeatedTask = new TimerTask() {
				public void run() {
					try {
				        logger.info("Recreating contextBuilder and context...");
				        createContextBuilder(certChainFilePath, privateKeyFilePath,
				    			trustCertCollectionFilePath, true);
					} catch (SSLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			};
			executor.scheduleAtFixedRate(repeatedTask, period, period, TimeUnit.MINUTES);
		}
	}
	
	private synchronized void createContextBuilder(File certChainFilePath, File privateKeyFilePath,
			File trustCertCollectionFilePath, boolean configured) throws SSLException {

		SslContextBuilder sslClientContextBuilder = SslContextBuilder.forServer(certChainFilePath,
				privateKeyFilePath);
		if (trustCertCollectionFilePath != null) {
			sslClientContextBuilder.trustManager(trustCertCollectionFilePath);
			sslClientContextBuilder.clientAuth(ClientAuth.REQUIRE);
		}
		if (configured) {
			sslClientContextBuilder = GrpcSslContexts.configure(sslClientContextBuilder, SslProvider.OPENSSL);
		}
		ctxBuilder = sslClientContextBuilder;
		ctx = ctxBuilder.build();
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
