# httpclient-wrappers
A small Java library for wrapping one or more HTTP client libraries in a single API.

There is one module for the wrapper API, and one each for the target HTTP client APIs. At the time of writing, those are [Java's built-in HTTP client (java.net.http)][1] and [Apache's HttpClient 5.x][2]. The idea is to include the JAR for the API, and then the JAR for the HTTP client of interest.

The code so far is focused on making it easier to deal with processing results from a response, which was the main goal at the time this was started. Coming soon: a wrapper for a customizable HTTP client builder; examples of how to use these APIs.

Each module has its own pom.xml file for now. We may switch to Gradle.


[1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html
[2]: https://hc.apache.org/httpcomponents-client-5.5.x/index.html