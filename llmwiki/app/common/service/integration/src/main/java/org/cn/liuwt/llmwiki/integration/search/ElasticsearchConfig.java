package org.cn.liuwt.llmwiki.integration.search;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

@Configuration
@ConditionalOnClass(name = "co.elastic.clients.elasticsearch.ElasticsearchClient")
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {

    @Bean
    public RestClient restClient(ElasticsearchProperties properties) {
        String[] uriArray = properties.getUris().split(",");
        HttpHost[] hosts = new HttpHost[uriArray.length];
        for (int i = 0; i < uriArray.length; i++) {
            hosts[i] = HttpHost.create(uriArray[i].trim());
        }

        RestClientBuilder builder = RestClient.builder(hosts);

        Header[] defaultHeaders = new Header[] {
            new BasicHeader("Accept", "application/json"),
            new BasicHeader("Content-Type", "application/json")
        };
        builder.setDefaultHeaders(defaultHeaders);

        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            if (properties.getUsername() != null && properties.getPassword() != null) {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword())
                );
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
            
            httpClientBuilder.addInterceptorLast(new HttpResponseInterceptor() {
                @Override
                public void process(HttpResponse response, HttpContext context) {
                    if (response.getFirstHeader("X-Elastic-Product") == null) {
                        response.addHeader("X-Elastic-Product", "Elasticsearch");
                    }
                }
            });
            
            return httpClientBuilder;
        });

        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        JacksonJsonpMapper mapper = new JacksonJsonpMapper();
        RestClientTransport transport = new RestClientTransport(restClient, mapper);
        return new ElasticsearchClient(transport);
    }
}