/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/jinganix/peashooter
 */

package io.github.jinganix.peashooter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Module encapsulation")
class ModuleEncapsulationTest {

  private static Path mainSourceRoot() {
    Path base = Path.of(System.getProperty("user.dir"));
    Path direct = base.resolve("src/main/java");
    return Files.exists(direct) ? direct : base.resolve("lib/src/main/java");
  }

  @Test
  @DisplayName("should hide the internal package behind a JPMS module when consumers are modular")
  void shouldHideInternalPackageBehindJpmsModule() throws IOException {
    // Given the main sources
    Path moduleInfo = mainSourceRoot().resolve("module-info.java");

    // When reading the module descriptor Then it must exist and hide internal
    assertThat(moduleInfo)
        .as("lib/src/main/java/module-info.java must exist to hide the internal package")
        .exists();
    String descriptor = Files.readString(moduleInfo);
    assertThat(descriptor).contains("module io.github.jinganix.peashooter");
    assertThat(descriptor).contains("exports io.github.jinganix.peashooter;");
    assertThat(descriptor).contains("exports io.github.jinganix.peashooter.executor;");
    assertThat(descriptor).contains("exports io.github.jinganix.peashooter.queue;");
    assertThat(descriptor).contains("exports io.github.jinganix.peashooter.trace;");
    assertThat(descriptor)
        .as("internal package must not be exported or opened")
        .doesNotContain("exports io.github.jinganix.peashooter.internal")
        .doesNotContain("opens io.github.jinganix.peashooter.internal");
  }

  @Test
  @DisplayName("should keep internal helpers out of public API signatures when scanned")
  void shouldKeepInternalHelpersOutOfPublicApiSignatures() throws Exception {
    // Given every public type of every package the module descriptor exports
    List<Class<?>> apiTypes = publicApiTypes();

    // Then the scan must cover the whole exported surface, not a hand-picked sample
    assertThat(apiTypes)
        .as("exported packages must expose public types and the scan must find them")
        .hasSizeGreaterThan(20);
    assertThat(apiTypes).extracting(Class::getPackageName).containsAll(exportedPackages());
    assertThat(mentionsInternal(io.github.jinganix.peashooter.internal.Keys.class))
        .as("the internal detector must recognise a known internal type")
        .isTrue();

    // And no public signature may mention an internal type
    List<String> leaks = new ArrayList<>();
    for (Class<?> type : apiTypes) {
      for (Method method : type.getMethods()) {
        if (mentionsInternal(method.getReturnType())
            || mentionsInternal(method.getParameterTypes())
            || mentionsInternal(method.getExceptionTypes())) {
          leaks.add(type.getName() + "#" + method.getName());
        }
      }
      for (Constructor<?> constructor : type.getConstructors()) {
        if (mentionsInternal(constructor.getParameterTypes())
            || mentionsInternal(constructor.getExceptionTypes())) {
          leaks.add(type.getName() + "#<init>");
        }
      }
      for (Field field : type.getFields()) {
        if (mentionsInternal(field.getType())) {
          leaks.add(type.getName() + "#" + field.getName());
        }
      }
    }
    assertThat(leaks).as("public API signatures must not reference internal types").isEmpty();
  }

  /**
   * Every public type in every exported package, discovered from the compiled module rather than
   * listed by hand: a leak in any exported type is caught, not only in the sampled ones.
   */
  private static List<Class<?>> publicApiTypes() throws Exception {
    Set<String> exported = exportedPackages();
    Path classes = mainClassesRoot();
    List<Class<?>> types = new ArrayList<>();
    try (var paths = Files.walk(classes)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(".class")) {
          continue;
        }
        String binaryName = binaryName(classes, path);
        String packageName =
            binaryName.contains(".") ? binaryName.substring(0, binaryName.lastIndexOf('.')) : "";
        if (!exported.contains(packageName)) {
          continue;
        }
        Class<?> type =
            Class.forName(binaryName, false, ModuleEncapsulationTest.class.getClassLoader());
        if (Modifier.isPublic(type.getModifiers())) {
          types.add(type);
        }
      }
    }
    return types;
  }

  /** Packages declared by {@code module-info.java} as {@code exports}. */
  private static Set<String> exportedPackages() throws IOException {
    String descriptor = Files.readString(mainSourceRoot().resolve("module-info.java"));
    Set<String> packages = new TreeSet<>();
    Matcher matcher = Pattern.compile("exports\\s+([\\w.]+)\\s*;").matcher(descriptor);
    while (matcher.find()) {
      packages.add(matcher.group(1));
    }
    assertThat(packages).as("module descriptor must export the public API packages").isNotEmpty();
    return packages;
  }

  /** Compiled main output directory derived from a loaded production class. */
  private static Path mainClassesRoot() throws Exception {
    Path location =
        Path.of(
            TaskQueueProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertThat(Files.isDirectory(location))
        .as("convention scan needs the compiled main classes directory, not %s", location)
        .isTrue();
    return location;
  }

  private static String binaryName(Path root, Path classFile) {
    String relative = root.relativize(classFile).toString();
    return relative
        .substring(0, relative.length() - ".class".length())
        .replace(File.separatorChar, '.');
  }

  private static boolean mentionsInternal(Class<?>... types) {
    for (Class<?> type : types) {
      Class<?> current = type;
      while (current.isArray()) {
        current = current.getComponentType();
      }
      if (current.getName().contains(".internal.")) {
        return true;
      }
    }
    return false;
  }
}
