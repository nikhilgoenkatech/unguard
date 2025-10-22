/*
 * Copyright 2023 Dynatrace LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dynatrace.ssrfservice;

import com.uber.jaeger.httpclient.Constants;
import com.uber.jaeger.httpclient.TracingResponseInterceptor;
import io.opentracing.Span;
import io.opentracing.Tracer;
io.opentracing.tag.Tags;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dynatrace.ssrfservice.tracing.EnhancedTracingRequestInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Base64;

@RestController
public class ProxyController {

    CloseableHttpClient httpclient;
    private final Tracer tracer;
    private final Logger logger = LogManager.getLogger(ProxyController.class);

    @Autowired
    public ProxyController(Tracer tracer) {
        this.tracer = tracer;
        HttpClientBuilder clientBuilder = HttpClients.custom();

        httpclient = clientBuilder
                .addInterceptorFirst(new EnhancedTracingRequestInterceptor(tracer))
                .addInterceptorFirst(new TracingResponseInterceptor())
                .build();
    }

    @RequestMapping("/")
    public String proxyUrlWithHttpClient(@RequestHeader("Host") String host, @RequestParam String url, @RequestParam String header) throws IOException {
        // SSRF mitigation: validate and restrict user-supplied URLs
        logger.info(url);
        tracer.activeSpan().setTag("http.host", host);

        // Only allow http/https URLs
        if (!url.matches("^(https?://)[^/]+(/.*)?$")) {
            throw new IllegalArgumentException("Invalid or unsupported URL format");
        }
        // Restrict to allow-listed domains (example: only allow public domains)
        String[] allowedDomains = {"example.com", "api.example.com"}; // TODO: update with your safe domains
        boolean allowed = false;
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            String hostName = parsedUrl.getHost();
            for (String domain : allowedDomains) {
                if (hostName.equalsIgnoreCase(domain)) {
                    allowed = true;
                    break;
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL");
        }
        if (!allowed) {
            throw new SecurityException("Access to this domain is not allowed");
        }

        CloseableHttpResponse execute = null;
        HttpContext httpContext = new BasicHttpContext();
        try {
            HttpGet httpget = new HttpGet(url);
            httpget.addHeader("Accept-Language", header);
            execute = httpclient.execute(httpget, httpContext);
            InputStream content = execute.getEntity().getContent();
            String s = IOUtils.toString(content, Charset.defaultCharset());
            execute.close();
            return s;
        } catch (Exception e) {
            finishCurrentSpanWithError(httpContext, e);
            return e.getMessage();
        } finally {
            if (execute != null) {
                execute.close();
            }
        }
    }

    private void finishCurrentSpanWithError(HttpContext httpContext, Exception e) {
        try {
            Span clientSpan = (Span) httpContext.getAttribute(Constants.CURRENT_SPAN_CONTEXT_KEY);
            if (clientSpan != null) {
                Tags.ERROR.set(clientSpan, true);
                clientSpan.log(e.getMessage());
                clientSpan.finish();
            } else {
                logger.warn("The ResponseInterceptor did not find a clientSpan.");
            }
        } catch (Exception ex) {
            logger.error("Could not finish client tracing span with error.", ex);
        }
    }

    private static String encodeBase64(byte[] imageBytes) {
        String imageString = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        try {
            Base64.Encoder encoder = Base64.getEncoder();
            imageString = encoder.encodeToString(imageBytes);

            bos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return imageString;
    }

    /**
     * Will fetch a jpg image from an URL and return the base64 image representation
     * WARNING: We never check if what we fetch is actually a valid image.
     *
     * @param url the URL to fetch data from
     * @return base64 jpg image
     * @throws IOException
     */
    @GetMapping(
            value = "/image",
            produces = MediaType.IMAGE_JPEG_VALUE
    )
    public @ResponseBody
    String proxyUrlWithCurl(@RequestParam String url) throws IOException {
        // Validate the URL to ensure it is safe
        if (!url.matches("^(https?://)[^/]+(/.*)?$")) {
            throw new IllegalArgumentException("Invalid or unsupported URL format");
        }
        // Restrict to allow-listed domains (example: only allow public domains)
        String[] allowedDomains = {"example.com", "api.example.com"}; // TODO: update with your safe domains
        boolean allowed = false;
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            String hostName = parsedUrl.getHost();
            for (String domain : allowedDomains) {
                if (hostName.equalsIgnoreCase(domain)) {
                    allowed = true;
                    break;
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL");
        }
        if (!allowed) {
            throw new SecurityException("Access to this domain is not allowed");
        }

        Span curlSpan = tracer.buildSpan("/image")
                .withTag("peer.address", url)
                .withTag(Tags.COMPONENT, "curl")
                .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT)
                .start();

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = client.execute(request)) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new IOException("Failed to fetch image, HTTP code: " + response.getStatusLine().getStatusCode());
                }
                byte[] imageBytes = IOUtils.toByteArray(response.getEntity().getContent());
                String base64Image = encodeBase64(imageBytes);
                return String.format("data:image/jpg;base64,%s", base64Image);
            }
        } catch (Exception e) {
            curlSpan.setTag(Tags.ERROR, true);
            curlSpan.log(e.getMessage());
            throw e;
        } finally {
            curlSpan.finish();
        }
    }
}
