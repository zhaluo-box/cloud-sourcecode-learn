/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.ribbon;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;

/**
 * @author Spencer Gibb
 * @author Dave Syer
 */
public class RibbonLoadBalancerClient implements LoadBalancerClient {

	// 属性
	private SpringClientFactory clientFactory;

	// 构造
	public RibbonLoadBalancerClient(SpringClientFactory clientFactory) {
		this.clientFactory = clientFactory;
	}

	/**
	 *重写  LoadBalancerClient接口的 reconstructURI
	 *
	 * @param instance
	 * @param original
	 * @return
	 * @author zhaluo
	 */
	@Override
	public URI reconstructURI(ServiceInstance instance, URI original) {
		//判断实例不为空
		Assert.notNull(instance, "instance can not be null");
		// 获取服务实例的服务名
		String serviceId = instance.getServiceId();
		//
		RibbonLoadBalancerContext context = this.clientFactory
				.getLoadBalancerContext(serviceId);
		Server server = new Server(instance.getHost(), instance.getPort());
		boolean secure = isSecure(server, serviceId);
		URI uri = original;
		if (secure) {
			uri = UriComponentsBuilder.fromUri(uri).scheme("https").build().toUri();
		}
		return context.reconstructURIWithServer(server, uri);
	}

	@Override
	public ServiceInstance choose(String serviceId) {
		Server server = getServer(serviceId);
		if (server == null) {
			return null;
		}
		return new RibbonServer(serviceId, server, isSecure(server, serviceId),
				serverIntrospector(serviceId).getMetadata(server));
	}

	/**
	 *  execute 方法:
	 *
	 * @param serviceId 服务名
	 * @param request  LoadBalancerRequest
	 * @param <T> 泛型
	 * @return
	 * @throws IOException
	 * @author zhaluo
	 */
	@Override
	public <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException {

		// 通过serviceid 获取 loadBalancer
		ILoadBalancer loadBalancer = getLoadBalancer(serviceId);
		// 通过 loadBalancer 获取服务
		Server server = getServer(loadBalancer);

		/* 如果服务不存在就 抛出异常*/
		if (server == null) {
			throw new IllegalStateException("No instances available for " + serviceId);
		}
		/*  包装成RibbonServer */
		RibbonServer ribbonServer = new RibbonServer(serviceId, server, isSecure(server,
				serviceId), serverIntrospector(serviceId).getMetadata(server));

		RibbonLoadBalancerContext context = this.clientFactory
				.getLoadBalancerContext(serviceId);
		RibbonStatsRecorder statsRecorder = new RibbonStatsRecorder(context, server);

		try {
			/**
			 * apply 向实际的服务实例发送请求
			 */
			T returnVal = request.apply(ribbonServer);
			statsRecorder.recordStats(returnVal);
			return returnVal;
		}
		// catch IOException and rethrow so RestTemplate behaves correctly
		catch (IOException ex) {
			statsRecorder.recordStats(ex);
			throw ex;
		}
		catch (Exception ex) {
			statsRecorder.recordStats(ex);
			ReflectionUtils.rethrowRuntimeException(ex);
		}
		return null;
	}

	private ServerIntrospector serverIntrospector(String serviceId) {
		ServerIntrospector serverIntrospector = this.clientFactory.getInstance(serviceId,
				ServerIntrospector.class);
		if (serverIntrospector == null) {
			serverIntrospector = new DefaultServerIntrospector();
		}
		return serverIntrospector;
	}

	private boolean isSecure(Server server, String serviceId) {
		IClientConfig config = this.clientFactory.getClientConfig(serviceId);
		if (config != null) {
			Boolean isSecure = config.get(CommonClientConfigKey.IsSecure);
			if (isSecure != null) {
				return isSecure;
			}
		}

		return serverIntrospector(serviceId).isSecure(server);
	}

	protected Server getServer(String serviceId) {
		return getServer(getLoadBalancer(serviceId));
	}

	protected Server getServer(ILoadBalancer loadBalancer) {
		if (loadBalancer == null) {
			return null;
		}
		return loadBalancer.chooseServer("default"); // TODO: better handling of key
	}

	protected ILoadBalancer getLoadBalancer(String serviceId) {
		return this.clientFactory.getLoadBalancer(serviceId);
	}

	/**
	 * RibbonServer 实现了 ServiceInstance
	 */
	protected static class RibbonServer implements ServiceInstance {
		/**
		 * 服务名
		 */
		private final String serviceId;
		/**
		 * 服务
		 */
		private final Server server;
		/**
		 * 是否采用HTTPS
		 */
		private final boolean secure;
		/**
		 * 元数据map
		 */
		private Map<String, String> metadata;

		/**
		 * 构造 默认不采用HTTPS 还有一个空的map
		 * @param serviceId
		 * @param server
		 */
		protected RibbonServer(String serviceId, Server server) {
			this(serviceId, server, false, Collections.<String, String> emptyMap());
		}

		protected RibbonServer(String serviceId, Server server, boolean secure,
				Map<String, String> metadata) {
			this.serviceId = serviceId;
			this.server = server;
			this.secure = secure;
			this.metadata = metadata;
		}

		@Override
		public String getServiceId() {
			return this.serviceId;
		}

		@Override
		public String getHost() {
			return this.server.getHost();
		}

		@Override
		public int getPort() {
			return this.server.getPort();
		}

		@Override
		public boolean isSecure() {
			return this.secure;
		}

		@Override
		public URI getUri() {
			return DefaultServiceInstance.getUri(this);
		}

		@Override
		public Map<String, String> getMetadata() {
			return this.metadata;
		}

		public Server getServer() {
			return this.server;
		}

		@Override
		public String toString() {
			final StringBuffer sb = new StringBuffer("RibbonServer{");
			sb.append("serviceId='").append(serviceId).append('\'');
			sb.append(", server=").append(server);
			sb.append(", secure=").append(secure);
			sb.append(", metadata=").append(metadata);
			sb.append('}');
			return sb.toString();
		}
	}

}
