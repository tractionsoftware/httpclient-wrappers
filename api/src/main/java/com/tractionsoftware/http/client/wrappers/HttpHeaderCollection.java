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

import com.google.common.collect.*;
import org.apache.commons.lang3.StringUtils;

import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Encapsulates a mutable collection of HTTP request or response headers, which preserves insertion order of the headers
 * that are added, either when a factory constructor method is invoked to supply initial values, or when headers are
 * added after initial creation.
 *
 * @author Dave Shepperton
 */
public final class HttpHeaderCollection {

    /**
     * A simple encapsulation of an HTTP header. The header's name is generally handled in a case-insensitive manner,
     * but must not be null.
     */
    public static final class Header {

        /**
         * Creates a new Header with the given name and value.
         *
         * @param name
         *     the name, which must not be null.
         * @param value
         *     the value, which can be null, in which case the value of the returned object will be the empty string.
         * @return a new Header with the given name and value.
         * @throws NullPointerException
         *     if the name is null.
         */
        public static Header createInstance(String name, String value) {
            Objects.requireNonNull(name, "name");
            return new Header(name, StringUtils.defaultString(value));
        }

        public static List<Header> createMultiple(String name, List<String> values) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(values, "values");
            List<Header> headers = new ArrayList<>(values.size());
            for (String value : values) {
                headers.add(new Header(name, value));
            }
            return headers;
        }

        private final String name;

        private final String value;

        private Header(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            String useValue;
            if (com.google.common.net.HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                useValue = "[-hidden-]";
            }
            else {
                useValue = value;
            }
            return name + "=" + useValue;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof Header otherHeader) {
                return StringUtils.equalsIgnoreCase(name, otherHeader.name) &&
                       Objects.equals(value, otherHeader.value);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(StringUtils.lowerCase(name), value);
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String toHttpString() {
            return name + ": " + value;
        }

        public boolean hasName(String name) {
            return StringUtils.equalsIgnoreCase(this.name, name);
        }

    }

    /**
     * Creates an initially empty instance.
     *
     * @return an initially empty instance.
     */
    public static HttpHeaderCollection createEmptyInstance() {
        return new HttpHeaderCollection();
    }

    /**
     * Creates an instance initially populated by the given set of {@link Header}s.
     *
     * @param headers
     *     the initial {@link Header} to use to populate the instance.
     * @return an instance initially populated by the given set of {@link Header}s.
     */
    public static HttpHeaderCollection createInstance(Iterable<Header> headers) {
        HttpHeaderCollection ret = new HttpHeaderCollection();
        ret.addAll(headers);
        return ret;
    }

    /**
     * Creates an instance initially populated with {@link Header}s created from the given name-to-header-values.
     *
     * @param name2values
     *     a map of name-to-header-values to be used as initial {@link Header}s to populate the instance.
     * @return an instance initially populated by the given set of {@link Header}s.
     */
    public static HttpHeaderCollection createInstance(Map<String,List<String>> name2values) {
        HttpHeaderCollection ret = new HttpHeaderCollection();
        ret.addAll(
            name2values.entrySet().stream()
                .map(e -> Header.createMultiple(e.getKey(), e.getValue()))
                .flatMap(List::stream)
                .iterator()
        );
        return ret;
    }

    /*
     * A list that maintains the order in which the headers were initially inserted, which is presumably the same as the
     * order in which thea appeared in an HTTP request or response.
     */
    private final LinkedList<Header> list;

    /*
     * A Multimap that supports quick retrieval of one or more headers by name. The keys are always lower-case.
     */
    private final ListMultimap<String,Header> name2headers;

    private HttpHeaderCollection() {
        this.list = new LinkedList<>();
        this.name2headers = LinkedListMultimap.create();
    }

    /**
     * Returns true if this instance does not contain any headers.
     *
     * @return true if this instance does not contain any headers; false otherwise.
     */
    public boolean isEmpty() {
        return list.isEmpty();
    }

    /**
     * Returns a {@link List} view of all {@link Header}s in the order in which they are currently tracked.
     *
     * @return a {@link List} view of all {@link Header}s in the order in which they are currently tracked.
     */
    public List<Header> all() {
        return Collections.unmodifiableList(list);
    }

    /**
     * Returns a {@link Multimap} view of the headers by name (case-insensitive). No guarantees are made about the order
     * in which the headers appear in any iteration other than that the headers for each name should appear in the order
     * in which they were inserted overall.
     *
     * @return a {@link Multimap} view of the headers by name (case-insensitive).
     */
    public Multimap<String,Header> asMultimap() {
        return Multimaps.unmodifiableMultimap(name2headers);
    }

    @Override
    public String toString() {
        return "{" + String.join(", ", headerStrings()) + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof HttpHeaderCollection otherHeaders) {
            return asMultimap().equals(otherHeaders.asMultimap());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return asMultimap().hashCode();
    }

    /**
     * Returns the list of values of headers that have the given name, if any.
     *
     * @param name
     *     the name of the header values to retrieve.
     * @return the list of values of headers that have the given name (case-insensitive), if any; or an empty list if
     *     the name is null or no headers are known for the given name.
     */
    public List<String> getValues(String name) {
        if (name == null) {
            return ImmutableList.of();
        }
        return name2headers.get(name.toLowerCase()).stream().map(Header::getValue).collect(Collectors.toList());
    }

    /**
     * Returns the first value of a header with the given name, if any; null otherwise.
     *
     * @param name
     *     the name of the headers to retrieve.
     * @return the list of headers with the given name, if any.
     */
    public String getFirstValue(String name) {
        return Iterables.getFirst(getValues(name), null);
    }

    /**
     * Sets the header with the given name to the given value. This has the effect of clearing any previously set
     * headers with the same name. If the given value is null, no new value will be set after the
     *
     * @param name
     *     the name of the header whose value is being set, which must not be null.
     * @param value
     *     the new single value of the header, which should be null if the intent is to clear any existing values
     *     without setting a new value.
     * @throws NullPointerException
     *     if the name is null.
     */
    public void set(String name, String value) {
        if (value == null) {
            clear(name);
        }
        else {
            set(Header.createInstance(name, value));
        }
    }

    /**
     * Sets the header with the given Header's name to the given value. This has the effect of clearing any previously
     * set headers with the same name.
     *
     * @param header
     *     the Header to set, which must not be null.
     */
    public void set(Header header) {
        Objects.requireNonNull(header, "header");
        clear(header.getName());
        add(header);
    }

    /**
     * Clears any headers that have the existing name.
     *
     * @param name
     *     the name of existing headers should be cleared. Note that name comparisons are done in a case-insensitive
     *     manner.
     */
    public void clear(String name) {
        Objects.requireNonNull(name, "name");
        list.removeIf(h -> h.hasName(name));
        name2headers.removeAll(StringUtils.lowerCase(name));
    }

    /**
     * Adds a new header for the given name and value.
     *
     * @param name
     *     the name of the header, which must not be null.
     * @param value
     *     the value of the header.
     * @throws NullPointerException
     *     if the name is null.
     */
    public void add(String name, String value) {
        add(Header.createInstance(name, value));
    }

    /**
     * Adds the given {@link Header}.
     *
     * @param header
     *     the {@link Header} to be added, which must not be null.
     */
    public void add(Header header) {
        Objects.requireNonNull(header, "header");
        list.add(header);
        name2headers.put(StringUtils.lowerCase(header.getName()), header);
    }

    /**
     * Adds all the given {@link Header}s.
     *
     * @param headers
     *     the {@link Header}s to add, which must not be null.
     * @throws NullPointerException
     *     if the headers object is null.
     */
    public void addAll(Iterable<Header> headers) {
        Objects.requireNonNull(headers, "headers");
        Iterables.addAll(list, headers);
        for (Header header : headers) {
            name2headers.put(StringUtils.lowerCase(header.getName()), header);
        }
    }

    /**
     * Adds all the given {@link Header}s.
     *
     * @param headers
     *     the {@link Header}s to add, which must not be null.
     * @throws NullPointerException
     *     if the headers object is null.
     */
    public void addAll(Iterator<Header> headers) {
        Objects.requireNonNull(headers, "headers");
        while (headers.hasNext()) {
            add(headers.next());
        }
    }

    /**
     * Prints an HTTP style rendering of all headers, in the order in which they are currently tracked, to the given
     * PrintWriter.
     *
     * @param out
     *     to which the rendering should be printed.
     */
    public void printHttp(PrintWriter out) {
        for (Header header : list) {
            out.println(header.toHttpString());
        }
    }

    /**
     * Prints an HTTP style rendering of all headers, in the order in which they are currently tracked, to the given
     * StringBuilder.
     *
     * @param buff
     *     to which the rendering should be printed.
     */
    public void printHttp(StringBuilder buff) {
        for (Header header : list) {
            buff.append(header.toHttpString()).append('\n');
        }
    }

    /*
     * Used in toString().
     */
    private Iterable<String> headerStrings() {
        return () -> list.stream().map(Header::toString).iterator();
    }

}
