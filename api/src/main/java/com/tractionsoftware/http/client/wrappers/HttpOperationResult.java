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

package com.tractionsoftware.http.client.wrappers;

import com.google.common.base.Suppliers;
import com.google.common.net.MediaType;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.lang.ref.Cleaner;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encapsulates an attempt to perform an operation via an HTTP request, and to process the response to create an
 * implementation-specific of result object. This encapsulation allows the creation of APIs that return result objects
 * to represent both failures and successes, allowing their clients to determine how to handle failures most
 * appropriately.
 *
 * <p>
 * This is <em>not</em> Yet-Another-Java-HTTP-Client. It is a tool for building client APIs in cases in which it is more
 * useful to always return a result object which may give access to some details of the HTTP request or response even if
 * the request or operation failed, whether during the request setup phase, the request initiation phase, the request
 * and response send/receive phase, or the response processing phase.
 *
 * <p>
 * The expectation is that to build such an API, clients of this API will use the factory constructor methods to create
 * instances that represent various types of successes and failures. The failure or success of the mechanical HTTP
 * request is separate from the failure or success of the operation so that, for example, the full set of response
 * headers can still be made available to clients even if the operation failed. Note also that there are separate
 * categories for failure-as-error and failure-as-warning so that a failed operation can be treated as an error or a
 * warning depending on implementation-specific considerations.
 *
 * <p>
 * Clients of this API also need to either use one of the client-specific implementation adapters in the other modules
 * (for the built-in Java HTTP client API or the Apache HttpComponents/HttpClient API), or will need to implement their
 * own adapter to use some other HTTP client API.
 *
 * @param <R>
 *     the type of request object.
 * @param <S>
 *     the type of response object.
 * @param <T>
 *     the type of result object.
 * @param <X>
 *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
 *     attempting to set up the request, when creating the result from the response, or in rare cases of other
 *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
 * @author Dave Shepperton
 */
public final class HttpOperationResult<R, S, T, X extends Exception> implements AutoCloseable {

    /**
     * Represents the status of the HTTP request and response.
     */
    public enum RequestStatus {

        /**
         * There was an error while attempting to set up the request. No request was issued, so no response was
         * received, and no processing performed.
         */
        ERROR_REQUEST_SETUP,

        /**
         * The request was successfully initiated, but there was a problem while attempting to send the full request or
         * read the response.
         */
        ERROR_IO,

        /**
         * The request was successfully initiated, but the thread was interrupted while sending the request, or while
         * reading the response. This can be caused by a timeout, or by some other interruption signal sent to the
         * thread performing the request.
         */
        ERROR_INTERRUPTED,

        /**
         * The request failed in an unexpected way.
         */
        ERROR_OTHER,

        /**
         * The request completed, the operation was successful, and a result was successfully produced from the
         * response.
         */
        SUCCESS

    }

    /**
     * Represents the status of requested operation, separate from the status of the request.
     */
    public enum OperationStatus {

        /**
         * The requested operation definitely did not complete because one of the error conditions from
         * {@link RequestStatus} was encountered while attempting to set up, initiate or send the request, or while
         * attempting to read the response.
         */
        NO_RESPONSE,

        /**
         * The request completed, but the server responded with one of the 4xx HTTP status response codes indicating
         * that the requested operation could not be performed because of a bad request, failure to match a
         * pre-condition, or some other condition.
         */
        FAILURE,

        /**
         * This is the same as {@link #FAILURE} except that the condition was not considered an error by the
         * implementation.
         */
        WARNING,

        /**
         * The request completed, but the server responded with one of the 5xx HTTP status response codes indicating
         * that the requested operation could not be performed because of some error unrelated to the validity of the
         * request such as an "internal" error or a failure to contact an upstream server.
         */
        FAILURE_SERVER_ERROR,

        /**
         * The request completed, the operation was nominally successful, but some error was encountered while
         * processing the server's response. This usually signifies that the response was unexpected or invalid.
         */
        FAILURE_RESPONSE_PROCESS,

        /**
         * The request completed, and a result object was successfully created from the response.
         */
        SUCCESS

    }

    /**
     * Represents an adapter for an object that represents an HTTP response. This is required to bridge the gap between
     * an HTTP client API-specific implementation object and this HttpResult wrapper API.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     */
    public interface ResponseAdapter<S, T> extends AutoCloseable {

        /**
         * Cleans up and closes any resources associated with the response.
         */
        void close();

        /**
         * Returns the response object.
         *
         * @return the response object.
         */
        S response();

        /**
         * Returns the HTTP response status code for the response.
         *
         * @return the HTTP response status code for the response.
         */
        int statusCode();

        /**
         * Returns the HTTP response headers.
         *
         * @return the HTTP response headers.
         */
        HttpHeaderCollection headers();

        /**
         * Returns true if a {@link #result()} object is available. If this method returns true, {@link #result()} must
         * not return null.
         *
         * @return true if a {@link #result()} object is available; false otherwise.
         */
        boolean hasResult();

        /**
         * Returns the result object that was created by reading the response body, if any. Multiple invocations of this
         * method should return the same result object, or should raise the same Exception, preferably without requiring
         * re-processing of the raw response.
         *
         * @return the result object that was created by reading the response body, if any; null otherwise.
         */
        T result();

    }

    public static abstract class AbstractResponseAdapter<S, T> implements ResponseAdapter<S,T> {

        protected final S response;

        protected final T result;

        public AbstractResponseAdapter(S response, T result) {
            this.response = response;
            this.result = result;
        }

        @Override
        public void close() {
            HttpOperationResult.consumeAndClose(result);
            HttpOperationResult.consumeAndClose(response);
        }

        @Override
        public final S response() {
            return response;
        }

        @Override
        public final boolean hasResult() {
            return result != null;
        }

        @Override
        public final T result() {
            return result;
        }

    }

    /**
     * Used to map the "Content-Type" header value to a {@link MediaType}.
     */
    public static final Function<HttpHeaderCollection,MediaType> CONTENT_TYPE_PARSER =
        (HttpHeaderCollection headers) -> {
            String contentTypeStr = headers.getFirstValue(com.google.common.net.HttpHeaders.CONTENT_TYPE);
            if (StringUtils.isBlank(contentTypeStr)) {
                return null;
            }
            try {
                return MediaType.parse(contentTypeStr);
            }
            catch (RuntimeException e) {
                Logger.getLogger(HttpOperationResult.class.getName())
                    .log(Level.WARNING, "Failed to parse content-type '" + contentTypeStr + "'", e);
                return null;
            }
        };

    /**
     * Represents a response/operation-status-specific wrapper for a response, taking into account whether a response
     * was even received.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static abstract class ResponseWrapper<S, T, X extends Exception> implements AutoCloseable {

        public String toString() {
            if (hasResponse()) {
                return getResponseSafe().toString();
            }
            return "[no response]";
        }

        /**
         * Cleans up and closes any resources associated with the response.
         */
        @Override
        public abstract void close();

        /**
         * Returns true if this wrapper actually has a response. If this method returns true, {@link #getResponse()} and
         * {@link #getResponseSafe()} must not return null (although getResponse() may still throw an Exception).
         *
         * @return true if this wrapper actually has a response; false otherwise.
         */
        public abstract boolean hasResponse();

        /**
         * Returns true if this wrapper actually has a result object. If this method returns true, {@link #getResult()}
         * and {@link #getResultSafe()} must not return null (although getResult() may still throw an Exception).
         *
         * @return true if this wrapper actually has a result object; false otherwise.
         */
        public abstract boolean hasResult();

        /**
         * Attempts to return the response object, if one is available and no error condition was identified at any
         * stage of the process, including setting up the request, sending the request, receiving the response, and
         * processing the response to generate a result object. This method will never return null; instead, if no
         * response is available, it throws an appropriate Exception.
         *
         * @return the response object, if one is available and no error condition was identified at any stage of the
         *     process.
         * @throws IOException
         *     if one was raised while attempting to send the request or receive the response.
         * @throws InterruptedException
         *     if the operation was interrupted while the request was being sent, or possibly while the response was
         *     being received or processed.
         * @throws X
         *     if one was raised while attempting to set up the request or process the response to generate a result
         *     object.
         */
        public abstract S getResponse() throws IOException, InterruptedException, X;

        /**
         * Returns the response object if one is available, without throwing any Exceptions as {@link #getResponse()}
         * may.
         *
         * @return the response object if one is available; null otherwise.
         */
        public abstract S getResponseSafe();

        /**
         * Returns the HTTP response status code if {@link #hasResponse()} a response is available.
         *
         * @return the HTTP response status code if {@link #hasResponse()} a response is available; -1 otherwise.
         */
        public final int getStatusCode() {
            if (hasResponse()) {
                return getStatusCodeImpl();
            }
            return -1;
        }

        /**
         * Returns the {@link HttpHeaderCollection} representing the response headers, if a response is available.
         *
         * @return the {@link HttpHeaderCollection} representing the response headers, if a response is available; an
         *     empty HttpHeaderCollection otherwise.
         */
        public final HttpHeaderCollection getHeaders() {
            if (hasResponse()) {
                return getHeadersImpl();
            }
            return HttpHeaderCollection.createEmptyInstance();
        }

        /**
         * Returns the result object, if one was generated. This method will never return null; instead, if no result
         * object is available, it throws an appropriate Exception.
         *
         * @return the result object, if one was generated.
         * @throws IOException
         *     if one was raised while attempting to send the request or receive the response.
         * @throws InterruptedException
         *     if the operation was interrupted while the request was being sent, or possibly while the response was
         *     being received or processed.
         * @throws X
         *     if one was raised while attempting to set up the request or process the response to generate a result
         *     object.
         */
        public final T getResult() throws IOException, InterruptedException, X {
            getResponse();
            return getResultImpl();
        }

        /**
         * Returns the result object, if one was generated.
         *
         * @return the result object, if one was generated; null otherwise.
         */
        public final T getResultSafe() {
            if (hasResult()) {
                try {
                    return getResultImpl();
                }
                catch (Exception e) {
                    Logger.getLogger(HttpOperationResult.class.getName())
                        .log(Level.FINE, "No result was available for this operation", e);
                }
            }
            return null;
        }

        /**
         * Returns the {@link RequestStatus} for the request.
         *
         * @return the {@link RequestStatus} for the request.
         */
        public abstract RequestStatus getRequestStatus();

        /**
         * Returns the {@link OperationStatus} for the request.
         *
         * @return the {@link OperationStatus} for the request.
         */
        public abstract OperationStatus getOperationStatus();

        /**
         * Returns the actual status code. This is only invoked if {@link #hasResponse()} returns true.
         *
         * @return the actual status code,
         */
        protected abstract int getStatusCodeImpl();

        /**
         * Returns an {@link HttpHeaderCollection} representing the actual response headers. This is only invoked if
         * {@link #hasResponse()} returns true.
         *
         * @return an {@link HttpHeaderCollection} representing the actual response headers.
         */
        protected abstract HttpHeaderCollection getHeadersImpl();

        /**
         * Returns the result object. This is only invoked if {@link #hasResult()} returns true.
         *
         * @return the result object.
         * @throws IOException
         *     if one was raised while attempting to send the request or receive the response.
         * @throws InterruptedException
         *     if the operation was interrupted while the request was being sent, or possibly while the response was
         *     being received or processed.
         * @throws X
         *     if one was raised while attempting to set up the request or process the response to generate a result
         *     object.
         */
        protected abstract T getResultImpl() throws IOException, InterruptedException, X;

    }

    /**
     * A {@link ResponseWrapper} for a situation in which no response is available due to an error, and which wraps the
     * Exception representing the error.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @param <Y>
     *     the actual type of Exception that this object wraps, which will generally be {@link IOException},
     *     {@link InterruptedException} or X.
     */
    private static abstract class NoResponseErrorResponseWrapper<S, T, X extends Exception, Y extends Exception>
        extends ResponseWrapper<S,T,X> {

        protected final Y error;

        private NoResponseErrorResponseWrapper(Y error) {
            this.error = error;
        }

        /**
         * This implementation does nothing.
         */
        @Override
        public final void close() {
        }

        /**
         * This implementation always returns false.
         */
        @Override
        public final boolean hasResponse() {
            return false;
        }

        /**
         * This implementation always returns null.
         */
        @Override
        public final S getResponseSafe() {
            return null;
        }

        /**
         * This implementation always returns false.
         */
        @Override
        public final boolean hasResult() {
            return false;
        }

        /**
         * This implementation always returns {@link OperationStatus#NO_RESPONSE}.
         */
        @Override
        public final OperationStatus getOperationStatus() {
            return OperationStatus.NO_RESPONSE;
        }

        /**
         * This implementation always returns -1, although it should never be invoked.
         */
        @Override
        protected final int getStatusCodeImpl() {
            return -1;
        }

        /**
         * This implementation always returns an empty {@link HttpHeaderCollection}, although it should never be
         * invoked.
         */
        @Override
        protected final HttpHeaderCollection getHeadersImpl() {
            return HttpHeaderCollection.createEmptyInstance();
        }

        /**
         * This implementation always returns null.
         */
        @Override
        protected final T getResultImpl() {
            return null;
        }

    }

    /**
     * A {@link ResponseWrapper} for a situation in which a response was received. It wraps a {@link ResponseAdapter},
     * and also wraps either an Exception representing an error identified while processing the response or a result
     * object of type T.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static abstract class ReceivedResponseWrapper<S, T, X extends Exception> extends ResponseWrapper<S,T,X> {

        protected final ResponseAdapter<S,T> response;

        private ReceivedResponseWrapper(ResponseAdapter<S,T> response) {
            this.response = response;
        }

        /**
         * This implementation closes the {@link ResponseAdapter}.
         */
        @Override
        public final void close() {
            response.close();
        }

        /**
         * This implementation always returns true.
         */
        @Override
        public final boolean hasResponse() {
            return true;
        }

        /**
         * This implementation always returns
         * {@link ResponseAdapter#response() the response wrapped by the ResponseAdapter} without raising an Exception.
         */
        @Override
        public final S getResponseSafe() {
            return response.response();
        }

        /**
         * This implementation always returns
         * {@link ResponseAdapter#statusCode() the status code from the ResponseAdapter}.
         */
        @Override
        protected final int getStatusCodeImpl() {
            return response.statusCode();
        }

        /**
         * This implementation always returns {@link ResponseAdapter#headers() the headers from the ResponseAdapter}.
         */
        @Override
        protected final HttpHeaderCollection getHeadersImpl() {
            return response.headers();
        }

        /**
         * This implementation always returns
         * {@link ResponseAdapter#result() the processed body from the ResponseAdapter}, which represents the result of
         * attempting to process the raw response.
         *
         * @return the result of {@link ResponseAdapter#result()}.
         */
        @Override
        protected final T getResultImpl() {
            return response.result();
        }

        /**
         * This implementation always returns {@link RequestStatus#SUCCESS}.
         */
        @Override
        public final RequestStatus getRequestStatus() {
            return RequestStatus.SUCCESS;
        }

    }

    /**
     * A {@link NoResponseErrorResponseWrapper} that represents a failure to set up the request. It wraps an Exception
     * of type X.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the type of Exception that may be produced when attempting to set up the request, or when creating the result
     *     from the response.
     */
    private static final class RequestSetupFailureErrorResponseWrapper<S, T, X extends Exception>
        extends NoResponseErrorResponseWrapper<S,T,X,X> {

        private RequestSetupFailureErrorResponseWrapper(X error) {
            super(error);
        }

        /**
         * This implementation always throws an Exception of type X.
         */
        @Override
        public S getResponse() throws X {
            throw error;
        }

        /**
         * This implementation always returns {@link RequestStatus#ERROR_REQUEST_SETUP}.
         */
        @Override
        public RequestStatus getRequestStatus() {
            return RequestStatus.ERROR_REQUEST_SETUP;
        }

    }

    /**
     * A {@link NoResponseErrorResponseWrapper} that represents an I/O failure that occurred while sending the request
     * or receiving the response. It wraps an IOException.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static final class IOFailureErrorResponseWrapper<S, T, X extends Exception>
        extends NoResponseErrorResponseWrapper<S,T,X,IOException> {

        private IOFailureErrorResponseWrapper(IOException error) {
            super(error);
        }

        /**
         * This implementation always throws an IOException representing the I/O failure.
         */
        @Override
        public S getResponse() throws IOException {
            throw error;
        }

        /**
         * This implementation always returns {@link RequestStatus#ERROR_IO}.
         */
        @Override
        public RequestStatus getRequestStatus() {
            return RequestStatus.ERROR_IO;
        }

    }

    /**
     * A {@link NoResponseErrorResponseWrapper} that represents the interruption of the attempt to initiate or send the
     * request, or in receiving or processing the response. It wraps an {@link InterruptedException}.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static final class InterruptedResponseWrapper<S, T, X extends Exception>
        extends NoResponseErrorResponseWrapper<S,T,X,InterruptedException> {

        private InterruptedResponseWrapper(InterruptedException error) {
            super(error);
        }

        /**
         * This implementation always throws an {@link InterruptedException}.
         */
        @Override
        public S getResponse() throws InterruptedException {
            throw error;
        }

        /**
         * This implementation always returns {@link RequestStatus#ERROR_INTERRUPTED}.
         */
        @Override
        public RequestStatus getRequestStatus() {
            return RequestStatus.ERROR_INTERRUPTED;
        }

    }

    /**
     * A {@link NoResponseErrorResponseWrapper} that represents some other unexpected failure encountered while
     * attempting to initiate or send the request, or to receive or process the response. It wraps an Exception of type
     * X.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static final class OtherErrorResponseWrapper<S, T, X extends Exception>
        extends NoResponseErrorResponseWrapper<S,T,X,X> {

        private OtherErrorResponseWrapper(X error) {
            super(error);
        }

        /**
         * This implementation always throws an Exception of type X.
         */
        @Override
        public S getResponse() throws X {
            throw error;
        }

        /**
         * This implementation always returns {@link RequestStatus#ERROR_OTHER}.
         */
        @Override
        public RequestStatus getRequestStatus() {
            return RequestStatus.ERROR_OTHER;
        }

    }

    /**
     * A {@link ReceivedResponseWrapper} that represents a failure during the processing of the response required to
     * create the result object, usually because the response was found to be invalid or unexpected in some way. It
     * wraps an Exception of type X, but also has a {@link ResponseAdapter}.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static final class ProcessingErrorResponseWrapper<S, T, X extends Exception>
        extends ReceivedResponseWrapper<S,T,X> {

        private final X error;

        private ProcessingErrorResponseWrapper(ResponseAdapter<S,T> response, X error) {
            super(response);
            this.error = error;
        }

        /**
         * This implementation always returns false.
         */
        @Override
        public boolean hasResult() {
            return false;
        }

        /**
         * This implementation always throws an Exception of type X.
         */
        @Override
        public S getResponse() throws X {
            throw error;
        }

        /**
         * This implementation always returns {@link OperationStatus#FAILURE_RESPONSE_PROCESS}.
         */
        @Override
        public OperationStatus getOperationStatus() {
            return OperationStatus.FAILURE_RESPONSE_PROCESS;
        }

    }

    /**
     * A {@link ReceivedResponseWrapper} that represents a successfully processed response. It wraps a result object of
     * type T.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static final class SuccessfulResultResponseWrapper<S, T, X extends Exception>
        extends ReceivedResponseWrapper<S,T,X> {

        private SuccessfulResultResponseWrapper(ResponseAdapter<S,T> response) {
            super(response);
        }

        /**
         * This implementation always returns true.
         */
        @Override
        public boolean hasResult() {
            return true;
        }

        /**
         * This implementation always returns
         * {@link ResponseAdapter#response() the response wrapped by the ResponseAdapter} without raising an Exception.
         */
        @Override
        public S getResponse() {
            return response.response();
        }

        /**
         * This implementation always returns {@link OperationStatus#SUCCESS}.
         */
        @Override
        public OperationStatus getOperationStatus() {
            return OperationStatus.SUCCESS;
        }

    }

    /**
     * A {@link ReceivedResponseWrapper} that represents a failure to perform the requested operation, and which has
     * been treated as an error by the context-specific client code. This will generally correspond to a response with
     * an HTTP response status code in the 4xx range. It wraps an Exception of type X.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static final class OperationFailureRequestWrapper<S, T, X extends Exception>
        extends ReceivedResponseWrapper<S,T,X> {

        private final X error;

        private OperationFailureRequestWrapper(ResponseAdapter<S,T> response, X error) {
            super(response);
            this.error = error;
        }

        /**
         * This implementation always returns false.
         */
        @Override
        public boolean hasResult() {
            return false;
        }

        /**
         * This implementation always throws an Exception of type X representing the failure condition.
         */
        @Override
        public S getResponse() throws X {
            throw error;
        }

        /**
         * This implementation always returns {@link OperationStatus#FAILURE}.
         */
        @Override
        public OperationStatus getOperationStatus() {
            return OperationStatus.FAILURE;
        }

    }

    /**
     * A {@link ReceivedResponseWrapper} that represents a failure to perform the requested operation, but which has
     * been treated as a warning rather than an error by the context-specific client code. That is, even if the server's
     * response indicated a failure of some sort, the context-specific code that handles and interprets the response
     * knows that the effective status of the operation was something other than a failure, and is still able to create
     * an appropriate result object. This will generally correspond to a response with an HTTP response status code
     * other than 200 (OK). It wraps an Exception of type X, but also wraps a result object of type T representing a
     * sort of no-op or ignored result.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static final class OperationWarningResponseWrapper<S, T, X extends Exception>
        extends ReceivedResponseWrapper<S,T,X> {

        private final X error;

        private OperationWarningResponseWrapper(ResponseAdapter<S,T> response, X error) {
            super(response);
            this.error = error;
        }

        /**
         * This implementation always returns true.
         */
        @Override
        public boolean hasResult() {
            return true;
        }

        /**
         * This implementation always throws an Exception of type X representing the warning condition. Use
         * {@link #getResponseSafe()} to retrieve the response without raising the Exception.
         */
        @Override
        public S getResponse() throws X {
            throw error;
        }

        /**
         * This implementation always returns {@link OperationStatus#WARNING}.
         */
        @Override
        public OperationStatus getOperationStatus() {
            return OperationStatus.WARNING;
        }

    }

    /**
     * A {@link ReceivedResponseWrapper} that represents a failure to perform the requested operation due to an error
     * that occurred on the remote server. This will generally correspond to a response with an HTTP response status
     * code in the 5xx range. It wraps an IOException.
     *
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     */
    private static final class OperationServerErrorResponseWrapper<S, T, X extends Exception>
        extends ReceivedResponseWrapper<S,T,X> {

        private final IOException error;

        private OperationServerErrorResponseWrapper(ResponseAdapter<S,T> response, IOException error) {
            super(response);
            this.error = error;
        }

        /**
         * This implementation always returns false.
         */
        @Override
        public boolean hasResult() {
            return false;
        }

        /**
         * This implementation always throws an IOException representing the error reported by the server. Use
         * {@link #getResponseSafe()} to retrieve the response without raising the Exception.
         */
        @Override
        public S getResponse() throws IOException {
            throw error;
        }

        /**
         * This implementation always returns {@link OperationStatus#FAILURE_SERVER_ERROR}.
         */
        @Override
        public OperationStatus getOperationStatus() {
            return OperationStatus.FAILURE_SERVER_ERROR;
        }

    }

    /**
     * Creates an HttpOperationResult representing an error that was encountered while attempting to set up the
     * request.
     *
     * @param error
     *     the Exception of type X representing the error.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the given error.
     * @throws NullPointerException
     *     if the error is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createInstanceForRequestSetupError(X error) {
        Objects.requireNonNull(error, "error");
        return new HttpOperationResult<>(null, new RequestSetupFailureErrorResponseWrapper<>(error));
    }

    /**
     * Creates an HttpOperationResult representing an I/O failure that occurred while sending the request or receiving
     * the response.
     *
     * @param request
     *     the request object.
     * @param error
     *     the IOException representing the failure.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the given error.
     * @throws NullPointerException
     *     if the request or error is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createInstanceForIOFailure(R request, IOException error) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(error, "error");
        return new HttpOperationResult<>(request, new IOFailureErrorResponseWrapper<>(error));
    }

    /**
     * Creates an HttpOperationResult representing an interruption of the initiation or sending of the request, or the
     * reception or processing of the response. This may be due to a timeout, or due to something inside the JVM
     * signaling the thread that was performing the operation.
     *
     * @param request
     *     the request object.
     * @param error
     *     the InterruptedException representing the interruption.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the given error.
     * @throws NullPointerException
     *     if the request or error is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createInstanceForRequestInterrupted(R request, InterruptedException error) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(error, "error");
        return new HttpOperationResult<>(request, new InterruptedResponseWrapper<>(error));
    }

    /**
     * Creates an HttpOperationResult representing some other unexpected failure encountered while attempting to
     * initiate or send the request, or to receive or process the response.
     *
     * @param request
     *     the request object.
     * @param error
     *     the Exception of type X representing the error.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the given error.
     * @throws NullPointerException
     *     if the request or error is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createInstanceForOtherError(R request, X error) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(error, "error");
        return new HttpOperationResult<>(request, new OtherErrorResponseWrapper<>(error));
    }

    /**
     * Creates an HttpOperationResult representing an operation that completed successfully, and for which it is already
     * known that a result object was successfully produced.
     *
     * @param request
     *     the request object.
     * @param response
     *     the {@link ResponseAdapter} for the response.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the successful operation.
     * @throws NullPointerException
     *     if the request or response is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createResultForSuccessfulOperation(R request, ResponseAdapter<S,T> response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        return new HttpOperationResult<>(request, new SuccessfulResultResponseWrapper<>(response));
    }

    /**
     * Creates an HttpOperationResult representing a failure to perform the operation which should be treated as a
     * warning rather than as an error. It should generally be the case that a result object was still produced from or
     * for the response, so the {@link #getResultSafe()} method of the returned instance should return a result, but
     * since an Exception was still raised, {@link #getResponse()} and {@link #getResult()} will both throw that
     * Exception.
     *
     * @param request
     *     the request object.
     * @param response
     *     the {@link ResponseAdapter} for the response.
     * @param error
     *     the Exception of type X representing the error.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the failure to be treated as a warning.
     * @throws NullPointerException
     *     if the request, response or error is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createResultForOperationFailureWarning(R request, ResponseAdapter<S,T> response, X error) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(error, "error");
        return new HttpOperationResult<>(request, new OperationWarningResponseWrapper<>(response, error));
    }

    /**
     * Creates an HttpOperationResult representing a problem with the response that prevented the creation of a result
     * object.
     *
     * @param request
     *     the request object.
     * @param response
     *     the {@link ResponseAdapter} for the response.
     * @param error
     *     the Exception of type X representing the error.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
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
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createInstanceForResponseProcessingError(R request, ResponseAdapter<S,T> response, X error) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(error, "error");
        return new HttpOperationResult<>(request, new ProcessingErrorResponseWrapper<>(response, error));
    }

    /**
     * Creates an HttpOperationResult representing a failure to perform the operation which should be treated as an
     * error.
     *
     * @param request
     *     the request object.
     * @param response
     *     the {@link ResponseAdapter} for the response.
     * @param error
     *     the Exception of type X representing the error.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the failure.
     * @throws NullPointerException
     *     if the request, response or error is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createResultForOperationFailure(R request, ResponseAdapter<S,T> response, X error) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(error, "error");
        return new HttpOperationResult<>(request, new OperationFailureRequestWrapper<>(response, error));
    }

    /**
     * Creates an HttpOperationResult representing the case of the server encountering an internal error while
     * attempting to perform the operation. An {@link IOException} will be created based on the response status code and
     * any available message.
     *
     * @param request
     *     the request object.
     * @param response
     *     the {@link ResponseAdapter} for the response.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the error.
     * @throws NullPointerException
     *     if the request or response is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createResultForServerError(R request, ResponseAdapter<S,T> response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        return createResultForServerError(request, response, createIOExceptionForHttpResponse(response));
    }

    /**
     * Creates an HttpOperationResult representing the case of the server encountering an internal error while
     * attempting to perform the operation.
     *
     * @param request
     *     the request object.
     * @param response
     *     the {@link ResponseAdapter} for the response.
     * @param error
     *     the IOException representing the server error.
     * @param <R>
     *     the type of request object.
     * @param <S>
     *     the type of response object.
     * @param <T>
     *     the type of result object.
     * @param <X>
     *     the context-specific type of Exception for the type of operation being attempted. It may be produced when
     *     attempting to set up the request, when creating the result from the response, or in rare cases of other
     *     unexpected errors (e.g., otherwise unhandled RuntimeExceptions).
     * @return a new HttpOperationResult representing the error.
     * @throws NullPointerException
     *     if the request, response or error is null.
     */
    public static <R, S, T, X extends Exception> HttpOperationResult<R,S,T,X> createResultForServerError(R request, ResponseAdapter<S,T> response, IOException error) {
        return new HttpOperationResult<>(request, new OperationServerErrorResponseWrapper<>(response, error));
    }

    /**
     * Returns the {@link Charset} from the first Content-Type header that appears in the given
     * {@link HttpHeaderCollection}, if such a header is present and has a charset= parameter.
     *
     * @param headers
     *     the {@link HttpHeaderCollection} to be examined.
     * @return the {@link Charset} from the first Content-Type header that appears in the given
     *     {@link HttpHeaderCollection}, if such a header is present and has a charset= parameter; null otherwise.
     * @throws NullPointerException
     *     if the headers object is null.
     */
    public static Charset getCharset(HttpHeaderCollection headers) {
        return getCharset(headers, StandardCharsets.UTF_8);
    }

    /**
     * Returns the {@link Charset} from the first Content-Type header that appears in the given
     * {@link HttpHeaderCollection}, if such a header is present and has a charset= parameter.
     *
     * @param headers
     *     the {@link HttpHeaderCollection} to be examined.
     * @param defaultCharset
     *     the default {@link Charset} to return if none can be identified.
     * @return the {@link Charset} from the first Content-Type header that appears in the given
     *     {@link HttpHeaderCollection}, if such a header is present and has a charset= parameter; the given default
     *     Charset otherwise.
     * @throws NullPointerException
     *     if the headers object is null.
     */
    public static Charset getCharset(HttpHeaderCollection headers, Charset defaultCharset) {
        Objects.requireNonNull(headers, "headers");
        MediaType contentType = CONTENT_TYPE_PARSER.apply(headers);
        if (contentType == null) {
            return defaultCharset;
        }
        Charset charset = contentType.charset().orNull();
        if (charset == null) {
            return defaultCharset;
        }
        return charset;
    }

    /**
     * Attempts to consume the given {@link InputStream} -- i.e., reading it fully -- before closing it. This method
     * will never throw an Exception, but will log a warning if one is encountered.
     *
     * @param input
     *     the {@link InputStream} to be consumed and closed.
     */
    public static void consumeAndClose(InputStream input) {
        try {
            input.transferTo(OutputStream.nullOutputStream());
        }
        catch (IOException e) {
            Logger.getLogger(HttpOperationResult.class.getName()).log(Level.FINE, "Failed to consume " + input, e);
        }
        finally {
            close(input);
        }
    }

    /**
     * Attempts to consume the given {@link Reader} -- i.e., reading it fully -- before closing it. This method will
     * never throw an Exception, but will log a warning if one is encountered.
     *
     * @param reader
     *     the {@link Reader} to be consumed and closed.
     */
    public static void consumeAndClose(Reader reader) {
        try {
            reader.transferTo(Writer.nullWriter());
        }
        catch (IOException e) {
            Logger.getLogger(HttpOperationResult.class.getName()).log(Level.FINE, "Failed to consume " + reader, e);
        }
        finally {
            close(reader);
        }
    }

    /**
     * Attempts to close the given {@link AutoCloseable}. This method will never throw an Exception, but will log a
     * warning if one is encountered.
     *
     * @param c
     *     the object to close.
     */
    public static void close(AutoCloseable c) {
        try {
            c.close();
        }
        catch (Exception e) {
            Logger.getLogger(HttpOperationResult.class.getName()).log(Level.FINE, "Failed to close " + c, e);
        }
    }

    /**
     * Attempts to consume -- by fully reading it, if applicable -- and close a result object that is supplied by the
     * given supplier. This method will never throw an Exception, but will log a warning if one is encountered.
     *
     * <ul>
     * <li>If the result cannot be retrieved or is null, no action is performed.</li>
     * <li>If the result is an {@link InputStream}, {@link #consumeAndClose(InputStream)} is invoked.</li>
     * <li>If the result is a {@link Reader}, {@link #consumeAndClose(Reader)} is invoked.</li>
     * <li>If the result is an {@link AutoCloseable}, {@link #close(AutoCloseable)} is invoked.</li>
     * </ul>
     *
     * @param object
     *     the object to consume and close, if possible.
     */
    public static void consumeAndClose(Object object) {
        if (object instanceof InputStream i) {
            consumeAndClose(i);
        }
        else if (object instanceof Reader r) {
            consumeAndClose(r);
        }
        else if (object instanceof AutoCloseable c) {
            close(c);
        }
    }

    private static IOException createIOExceptionForHttpResponse(ResponseAdapter<?,?> response) {
        return new IOException(getMessageForHttpStatusResponseCode(response.statusCode()));
    }

    private static String getMessageForHttpStatusResponseCode(int statusCode) {
        // TODO: i18n
        if (statusCode < 0) {
            return "Request Incomplete";
        }
        return "HTTP " + statusCode;
    }

    /*
     * Used to clean up all ResponseWrapper instances.
     */
    private static final Cleaner CLEANER = Cleaner.create();

    /*
     * The request object, which may be null.
     */
    private final R request;

    /*
     * The ResponseAdapter object, which will never be null.
     */
    private final ResponseWrapper<S,T,X> responseWrapper;

    /*
     * Retrieves and memoizes the Content-Type response header.
     */
    private final Supplier<MediaType> responseContentType = Suppliers.memoize(this::parseResponseContentTypeHeader);

    /*
     * The cleanup action to be invoked by the close method, which will have the effect of removing it from the cleaner
     * queue.
     */
    private final Cleaner.Cleanable closer;

    private HttpOperationResult(R request, ResponseWrapper<S,T,X> responseWrapper) {
        this.request = request;
        this.responseWrapper = responseWrapper;
        this.closer = CLEANER.register(this, responseWrapper::close);
    }

    @Override
    public String toString() {
        return request + ";" + responseWrapper + "[" + toResponseStatusString() + "]";
    }

    /**
     * Returns a String that includes the HTTP response status code and a status message, if one is available.
     *
     * @return a String that includes the HTTP response status code and a status message, if one is available.
     */
    public String toResponseStatusString() {
        int statusCode = getResponseStatusCode();
        if (statusCode > 0) {
            return statusCode + ": " + getMessageForHttpStatusResponseCode(statusCode);
        }
        return getMessageForHttpStatusResponseCode(-1);
    }

    /**
     * This implementation closes the response, if one is present. Clients creating HttpOperationResult instances should
     * take care that the {@link ResponseAdapter} supplied to the factory constructor properly implement
     * {@link ResponseAdapter#close()} in an implementation-specific manner.
     */
    @Override
    public void close() {
        closer.clean();
    }

    /**
     * Returns true if the request completed successfully and the response was received, regardless of whether
     * {@link #operationSucceeded() the operation succeeded}.
     *
     * @return true if the request completed successfully and the response was received, regardless of whether the
     *     operation succeeded; false otherwise.
     */
    public boolean requestCompleted() {
        return responseWrapper.hasResponse();
    }

    /**
     * Returns true if the operation was completed successfully.
     *
     * @return true if the operation was completed successfully; false otherwise.
     */
    public boolean operationSucceeded() {
        return responseWrapper.getOperationStatus() == OperationStatus.SUCCESS;
    }

    /**
     * Returns the {@link RequestStatus} for the request.
     *
     * @return the {@link RequestStatus} for the request.
     */
    public RequestStatus getRequestStatus() {
        return responseWrapper.getRequestStatus();
    }

    /**
     * Returns the {@link OperationStatus} of the operation.
     *
     * @return the {@link OperationStatus} of the operation.
     */
    public OperationStatus getOperationStatus() {
        return responseWrapper.getOperationStatus();
    }

    /**
     * Returns the request if one was successfully set up.
     *
     * @return the request if one was successfully set up; null otherwise.
     */
    public R getRequest() {
        return request;
    }

    /**
     * Returns the HTTP response status code, if the request completed successfully and the response was received.
     *
     * @return the HTTP response status code, if the request completed successfully and the response was received; -1
     *     otherwise.
     */
    public int getResponseStatusCode() {
        return responseWrapper.getStatusCode();
    }

    /**
     * Returns the message text corresponding to the HTTP response status code, if the request completed successfully
     * and the response was received.
     *
     * @return the message text corresponding to the HTTP response status code, if the request completed successfully
     *     and the response was received; the empty string otherwise.
     */
    public String getResponseStatusMessage() {
        return getMessageForHttpStatusResponseCode(getResponseStatusCode());
    }

    /**
     * Returns the {@link HttpHeaderCollection} representing the response headers, if the request completed successfully
     * and the response was received.
     *
     * @return the {@link HttpHeaderCollection} representing the response headers, if the request completed successfully
     *     and the response was received; an empty HttpHeaders object otherwise.
     */
    public HttpHeaderCollection getResponseHeaders() {
        return responseWrapper.getHeaders();
    }

    /**
     * Returns the {@link MediaType} representing the Content-Type of the response.
     *
     * @return the {@link MediaType} representing the Content-Type of the response.
     */
    public MediaType getResponseContentType() {
        return responseContentType.get();
    }

    /**
     * Returns the base Content-Type of the response, without parameters, if one could be determined from the response.
     *
     * @return the base Content-Type of the response, without parameters, if one could be determined from the response;
     *     null otherwise.
     */
    public MediaType getBaseResponseContentType() {
        MediaType contentType = responseContentType.get();
        if (contentType == null) {
            return null;
        }
        return contentType.withoutParameters();
    }

    /**
     * Returns the Content-Type as a String, if one could be determined from the response.
     *
     * @return the Content-Type as a String, if one could be determined from the response; null otherwise.
     */
    public String getResponseContentTypeString() {
        MediaType contentType = getResponseContentType();
        if (contentType == null) {
            return null;
        }
        return contentType.toString();
    }

    /**
     * Returns the {@link Charset} from the Content-Type, if one could be determined from the response and had a
     * charset= parameter. If none could be determined, or none was present, {@link StandardCharsets#UTF_8} is returned
     * instead.
     *
     * @return the {@link Charset} from the Content-Type, if one could be determined from the response and had a
     *     charset= parameter; {@link StandardCharsets#UTF_8} otherwise.
     */
    public Charset getResponseCharset() {
        return getResponseCharset(StandardCharsets.UTF_8);
    }

    /**
     * Returns the {@link Charset} from the Content-Type, if one could be determined from the response and had a
     * charset= parameter. If none could be determined, or none was present, the given default Charset is returned
     * instead.
     *
     * @param defaultCharset
     *     the {@link Charset} to be returned by default if no Content-Type could be determined from the response, or if
     *     the Content-Type did not have a charset= parameter.
     * @return the {@link Charset} from the Content-Type, if one could be determined from the response and had a
     *     charset= parameter; the given default Charset otherwise.
     */
    public Charset getResponseCharset(Charset defaultCharset) {
        MediaType contentType = getResponseContentType();
        if (contentType == null) {
            return defaultCharset;
        }
        return contentType.charset().or(StandardCharsets.UTF_8);
    }

    /**
     * Returns the response object if the request completed without failure or error. If a failure or error of any kind
     * was encountered, this method throws a corresponding Exception. To retrieve the response, if one is available,
     * without throwing such an Exception, use {@link #getResponseSafe()}.
     *
     * @return the response object if the request completed without failure or error.
     * @throws IOException
     *     if an IO error prevented the request being sent or the response from being received.
     * @throws InterruptedException
     *     if the attempt to send the request or read or process the response was interrupted.
     * @throws X
     *     if some other error occurred that prevented the request from being initiated or from completing.
     */
    public S getResponse() throws IOException, InterruptedException, X {
        return responseWrapper.getResponse();
    }

    /**
     * Returns the response object if one was retrieved. Unlike {@link #getResponse()}, this method never throws an
     * Exception, but may return null.
     *
     * @return the response object if one was retrieved; null otherwise.
     */
    public S getResponseSafe() {
        return responseWrapper.getResponseSafe();
    }

    /**
     * Returns the result object if the request completed without failure or error and a result was successfully
     * produced. If a failure or error of any kind was encountered, this method throws a corresponding Exception. To
     * retrieve the result object, if one is available, without throwing such an Exception, use
     * {@link #getResultSafe()}.
     *
     * @return the result object if the request completed without failure or error and a result was successfully
     *     produced.
     * @throws IOException
     *     if an IO error prevented the request being sent or the response from being received.
     * @throws InterruptedException
     *     if the attempt to send the request or read or process the response was interrupted.
     * @throws X
     *     if some other error occurred that prevented the request from being initiated or from completing, or if an
     *     error was encountered while processing the response that prevented the result object from being created.
     */
    public T getResult() throws IOException, InterruptedException, X {
        return responseWrapper.getResult();
    }

    /**
     * Returns the result object if the request completed without failure or error and a result was successfully
     * produced.  Unlike {@link #getResult()}, this method never throws an Exception, but may return null.
     *
     * @return the result object if the request completed without failure or error and a result was successfully
     *     produced; null otherwise.
     */
    public T getResultSafe() {
        return responseWrapper.getResultSafe();
    }

    /**
     * Returns the error message from the server, if the server reported a request failure or an internal error.
     *
     * @return the error message from the server, if the server reported a request failure or an internal error; null
     *     otherwise.
     */
    public String getServerErrorMessage() {
        return switch (getOperationStatus()) {
            case FAILURE, FAILURE_SERVER_ERROR -> toResponseStatusString();
            default -> null;
        };
    }

    /**
     * Used to determine the Content-Type for the response.
     *
     * @return the {@link MediaType} representing the parsed Content-Type header from the response, if one could be
     *     determined; null otherwise.
     */
    private MediaType parseResponseContentTypeHeader() {
        return CONTENT_TYPE_PARSER.apply(responseWrapper.getHeaders());
    }

}
