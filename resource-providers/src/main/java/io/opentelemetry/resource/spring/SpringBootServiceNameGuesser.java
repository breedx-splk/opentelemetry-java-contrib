/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.resource.spring;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * A ResourceProvider that will attempt to guess the application name for a Spring Boot service.
 * When successful, it will return a Resource that has the service name attribute populated with the
 * name of the Spring Boot application. It uses the following strategies, and the first successful
 * strategy wins:
 *
 * <ul>
 *   <li>Check for the SPRING_APPLICATION_NAME environment variable
 *   <li>Check for spring.application.name system property
 *   <li>Check for application.properties file on the classpath
 *   <li>Check for application.properties in the current working dir
 *   <li>Check for application.yml on the classpath
 *   <li>Check for application.yml in the current working dir
 *   <li>Check for --spring.application.name program argument (not jvm arg) via ProcessHandle
 *   <li>Check for --spring.application.name program argument via sun.java.command system property
 * </ul>
 */
@AutoService(ResourceProvider.class)
public class SpringBootServiceNameGuesser implements ResourceProvider {

  private static final Pattern COMMANDLINE_PATTERN =
      Pattern.compile(".*--spring\\.application\\.name=([a-zA-Z.\\-_]+).*");
  private static final Logger logger = Logger.getLogger(SpringBootServiceNameGuesser.class.getName());
  private final SystemHelper system;

  public SpringBootServiceNameGuesser() {
    this(new SystemHelper());
  }

  // Exists for testing
  SpringBootServiceNameGuesser(SystemHelper system) {
    this.system = system;
  }

  @Override
  public Resource createResource(ConfigProperties config) {

    logger.debug("Performing Spring Boot service name auto-detection...");
    Stream<Supplier<String>> finders =
        Stream.of(
            this::findByEnvironmentVariable,
            this::findBySystemProperties,
            this::findByClasspathApplicationProperties,
            this::findByCurrentDirectoryApplicationProperties,
            this::findByClasspathApplicationYaml,
            this::findByCurrentDirectoryApplicationYaml,
            this::findByCommandlineArgument);
    return finders
        .map(Supplier::get)
        .filter(Objects::nonNull)
        .findFirst()
        .map(
            serviceName -> {
              logger.debug("Guessed Spring Boot service name: " + serviceName);
              return Resource.builder().put(ResourceAttributes.SERVICE_NAME, serviceName).build();
            })
        .orElseGet(Resource::empty);
  }

  @Nullable
  private String findByEnvironmentVariable() {
    String result = system.getenv("SPRING_APPLICATION_NAME");
    logger.debug("Checking for SPRING_APPLICATION_NAME in env: " + result);
    return result;
  }

  @Nullable
  private String findBySystemProperties() {
    String result = system.getProperty("spring.application.name");
    logger.debug("Checking for spring.application.name system property: " + result);
    return result;
  }

  @Nullable
  private String findByClasspathApplicationProperties() {
    String result = readNameFromAppProperties();
    logger.debug("Checking for spring.application.name in application.properties file: " + result);
    return result;
  }

  @Nullable
  private String findByCurrentDirectoryApplicationProperties() {
    String result = null;
    try (InputStream in = system.openFile("application.properties")) {
      result = getAppNamePropertyFromStream(in);
    } catch (Exception e) {
      // expected to fail sometimes
    }
    logger.debug("Checking application.properties in current dir: " + result);
    return result;
  }

  @Nullable
  private String findByClasspathApplicationYaml() {
    String result =
        loadFromClasspath("application.yml", SpringBootServiceNameGuesser::parseNameFromYaml);
    logger.debug("Checking application.yml in classpath: " + result);
    return result;
  }

  @Nullable
  private String findByCurrentDirectoryApplicationYaml() {
    String result = null;
    try (InputStream in = system.openFile("application.yml")) {
      result = parseNameFromYaml(in);
    } catch (Exception e) {
      // expected to fail sometimes
    }
    logger.debug("Checking application.yml in current dir: " + result);
    return result;
  }

  @Nullable
  @SuppressWarnings("unchecked")
  private static String parseNameFromYaml(InputStream in) {
    Yaml yaml = new Yaml();
    try {
      Map<String, Object> data = yaml.load(in);
      Map<String, Map<String, Object>> spring =
          (Map<String, Map<String, Object>>) data.get("spring");
      if (spring != null) {
        Map<String, Object> app = spring.get("application");
        if (app != null) {
          Object name = app.get("name");
          if (name != null) {
            return (String) name;
          }
        }
      }
    } catch (RuntimeException e) {
      // expected to fail sometimes
    }
    return null;
  }

  @Nullable
  private String findByCommandlineArgument() {
    String result = attemptProcessHandleReflection();
    if (result == null) {
      String javaCommand = system.getProperty("sun.java.command");
      result = parseNameFromCommandLine(javaCommand);
    }
    logger.debug("Checking application commandline args: " + result);
    return result;
  }

  @Nullable
  private String attemptProcessHandleReflection() {
    try {
      String commandLine = system.attemptGetCommandLineViaReflection();
      return parseNameFromCommandLine(commandLine);
    } catch (Exception e) {
      return null;
    }
  }

  private static String parseNameFromCommandLine(String commandLine) {
    Matcher matcher = COMMANDLINE_PATTERN.matcher(commandLine);
    boolean ignored = matcher.find(); // Required before group()
    return matcher.group(1);
  }

  @Nullable
  private String readNameFromAppProperties() {
    return loadFromClasspath(
        "application.properties", SpringBootServiceNameGuesser::getAppNamePropertyFromStream);
  }

  @Nullable
  private static String getAppNamePropertyFromStream(InputStream in) {
    Properties properties = new Properties();
    try {
      properties.load(in);
      return properties.getProperty("spring.application.name");
    } catch (IOException e) {
      return null;
    }
  }

  @Nullable
  private String loadFromClasspath(String filename, Function<InputStream, String> parser) {
    try (InputStream in = system.openClasspathResource(filename)) {
      return parser.apply(in);
    } catch (Exception e) {
      return null;
    }
  }

  // Exists for testing
  static class SystemHelper {

    String getenv(String name) {
      return System.getenv(name);
    }

    String getProperty(String key) {
      return System.getProperty(key);
    }

    InputStream openClasspathResource(String filename) {
      return ClassLoader.getSystemClassLoader().getResourceAsStream(filename);
    }

    InputStream openFile(String filename) throws Exception {
      return Files.newInputStream(Paths.get(filename));
    }

    /**
     * Attempts to use ProcessHandle to get the full commandline of the current process. Will only
     * succeed on java 9+.
     */
    @SuppressWarnings("unchecked")
    String attemptGetCommandLineViaReflection() throws Exception {
      Class<?> clazz = Class.forName("java.lang.ProcessHandle");
      Method currentMethod = clazz.getDeclaredMethod("current");
      Method infoMethod = clazz.getDeclaredMethod("info");
      Object currentInstance = currentMethod.invoke(null);
      Object info = infoMethod.invoke(currentInstance);
      Method commandLineMethod = info.getClass().getInterfaces()[0].getMethod("commandLine");
      Object optionalCommandLine = commandLineMethod.invoke(info);
      return ((Optional<String>) optionalCommandLine).get();
    }
  }
}