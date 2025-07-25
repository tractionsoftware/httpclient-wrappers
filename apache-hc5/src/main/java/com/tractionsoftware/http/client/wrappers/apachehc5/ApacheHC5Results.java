/*
 *
 *    Copyright 2023 Traction Software, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.tractionsoftware.http.client.wrappers.apachehc5;

import com.tractionsoftware.http.client.wrappers.HttpHeaderCollection;
import com.tractionsoftware.http.client.wrappers.HttpOperationResult;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Provides {@link HttpOperationResult}s for {@link HttpRequest}s and {@link ClassicHttpResponse}s from version 5.x of
 * Apache's HttpComponents HttpClient API. We use ClassicHttpResponse because it supports enclosure of an
 * {@link org.apache.hc.core5.http.HttpEntity} in the response, which is a desirable characteristic.
 *
 * @author Dave Shepperton
 */
public final class ApacheHC5Results {

    // Not instantiable.
    private ApacheHC5Results() {
    }

    private static final class ApacheHC5ResponseAdapter<T>
        extends HttpOperationResult.AbstractResponseAdapter<ClassicHttpResponse,T> {

        private ApacheHC5ResponseAdapter(ClassicHttpResponse response, T body) {
            super(response, body);
        }

        @Override
        public void close() {
            HttpOperationResult.consumeAndClose(result);
            EntityUtils.consumeQuietly(response.getEntity());
            HttpOperationResult.consumeAndClose(response);
        }

        @Override
        public int statusCode() {
            return response.getCode();
        }

        /**
         * @implNote Since Apache HC5 HTTP client API supports retrieving response headers in the order in which
         *     they were encountered, the {@link HttpHeaderCollection} returned by this implementation will correctly
         *     reflect that order.
         */
        @Override
        public HttpHeaderCollection headers() {
            return HttpHeaderCollection.createInstance(headerStream().collect(Collectors.toList()));
        }

        private Stream<HttpHeaderCollection.Header> headerStream() {
            return Arrays.stream(response.getHeaders())
                .map(h -> HttpHeaderCollection.Header.createInstance(h.getName(), h.getValue()));
        }

    }

    /**
     * Creates an {@link HttpOperationResult} representing an error that was encountered while attempting to set up the
     * request.
     *
     * @param error
     *     the Exception of type X representing the error.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the given error.
     * @throws NullPointerException
     *     if the error is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createInstanceForRequestSetupError(X error) {
        return HttpOperationResult.createInstanceForRequestSetupError(error);
    }

    /**
     * Creates an {@link HttpOperationResult} representing an I/O failure that occurred while sending the request or
     * receiving the response.
     *
     * @param request
     *     the request object.
     * @param error
     *     the IOException representing the failure.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the given error.
     * @throws NullPointerException
     *     if the request or error is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createInstanceForRequestIOFailure(HttpRequest request, IOException error) {
        return HttpOperationResult.createInstanceForIOFailure(request, error);
    }

    /**
     * Creates an {@link HttpOperationResult} representing an interruption of the initiation or sending of the request,
     * or the reception or processing of the response. This may be due to a timeout, or due to something inside the JVM
     * signaling the thread that was performing the operation.
     *
     * @param request
     *     the request object.
     * @param error
     *     the InterruptedException representing the interruption.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the given error.
     * @throws NullPointerException
     *     if the request or error is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createInstanceForRequestInterrupted(HttpRequest request, InterruptedException error) {
        return HttpOperationResult.createInstanceForRequestInterrupted(request, error);
    }

    /**
     * Creates an {@link HttpOperationResult} representing some other unexpected failure encountered while attempting to
     * initiate or send the request, or to receive or process the response.
     *
     * @param request
     *     the request object.
     * @param error
     *     the Exception of type X representing the error.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the given error.
     * @throws NullPointerException
     *     if the request or error is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createInstanceForOtherError(HttpRequest request, X error) {
        return HttpOperationResult.createInstanceForOtherError(request, error);
    }

    /**
     * Creates an {@link HttpOperationResult} representing an operation that completed successfully, and for which it is
     * already known that a result object was successfully produced.
     *
     * @param request
     *     the request object.
     * @param response
     *     the response object.
     * @param body
     *     the result object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the successful operation.
     * @throws NullPointerException
     *     if the request or response is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createResultForSuccessfulOperation(HttpRequest request, ClassicHttpResponse response, T body) {
        return HttpOperationResult.createResultForSuccessfulOperation(
            request,
            new ApacheHC5ResponseAdapter<>(response, body)
        );
    }

    /**
     * Creates an {@link HttpOperationResult} representing a failure to perform the operation which should be treated as
     * a warning rather than as an error. It should generally be the case that a result object was still produced from
     * or for the response, so the {@link HttpOperationResult#getResultSafe()} method of the returned instance should
     * return a result, but since an Exception was still raised, {@link HttpOperationResult#getResponse()} and
     * {@link HttpOperationResult#getResult()} will both throw that Exception.
     *
     * @param request
     *     the request object.
     * @param response
     *     the response object.
     * @param error
     *     the Exception of type X representing the error.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the failure to be treated as a warning.
     * @throws NullPointerException
     *     if the request, response or error is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createResultForOperationFailureWarning(HttpRequest request, ClassicHttpResponse response, T body, X error) {
        return HttpOperationResult.createResultForOperationFailureWarning(
            request,
            new ApacheHC5ResponseAdapter<>(response, body),
            error
        );
    }

    /**
     * Creates an HttpOperationResult representing a problem with the response that prevented the creation of a result
     * object.
     *
     * @param request
     *     the request object.
     * @param response
     *     the response object.
     * @param error
     *     the Exception of type X representing the error.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the given error.
     * @throws NullPointerException
     *     if the request, response or error is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createInstanceForResponseProcessingError(HttpRequest request, ClassicHttpResponse response, T body, X error) {
        Objects.requireNonNull(response, "response");
        return HttpOperationResult.createInstanceForResponseProcessingError(
            request,
            new ApacheHC5ResponseAdapter<>(response, body),
            error
        );
    }

    /**
     * Creates an {@link HttpOperationResult} representing a failure to perform the operation which should be treated as
     * an error.
     *
     * @param request
     *     the request object.
     * @param response
     *     the response object.
     * @param error
     *     the Exception of type X representing the error.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the failure.
     * @throws NullPointerException
     *     if the request, response or error is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createResultForOperationFailure(HttpRequest request, ClassicHttpResponse response, T body, X error) {
        return HttpOperationResult.createResultForOperationFailure(
            request,
            new ApacheHC5ResponseAdapter<>(response, body),
            error
        );
    }

    /**
     * Creates an {@link HttpOperationResult} representing the case of the server encountering an internal error while
     * attempting to perform the operation. An {@link IOException} will be created based on the response status code and
     * any available message.
     *
     * @param request
     *     the request object.
     * @param response
     *     the response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the error.
     * @throws NullPointerException
     *     if the request or response is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createResultForServerError(HttpRequest request, ClassicHttpResponse response, T body) {
        return HttpOperationResult.createResultForServerError(request, new ApacheHC5ResponseAdapter<>(response, body));
    }

    /**
     * Creates an {@link HttpOperationResult} representing the case of the server encountering an internal error while
     * attempting to perform the operation.
     *
     * @param request
     *     the request object.
     * @param response
     *     the response object.
     * @param error
     *     the IOException representing the server error.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new {@link HttpOperationResult} representing the error.
     * @throws NullPointerException
     *     if the request, response or error is null.
     */
    public static <T, X extends Exception> HttpOperationResult<HttpRequest,ClassicHttpResponse,T,X> createResultForServerError(HttpRequest request, ClassicHttpResponse response, T body, IOException error) {
        return HttpOperationResult.createResultForServerError(
            request,
            new ApacheHC5ResponseAdapter<>(response, body),
            error
        );
    }

}
