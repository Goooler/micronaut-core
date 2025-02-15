/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.inject;

import io.micronaut.core.annotation.AnnotatedElement;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.NextMajorVersion;
import io.micronaut.core.type.ArgumentCoercible;

import java.lang.reflect.Field;

/**
 * Defines an injection point for a field.
 *
 * @param <B> The bean type that declares the injection point
 * @param <T> The field component type
 * @author Graeme Rocher
 * @since 1.0
 */
public interface FieldInjectionPoint<B, T> extends InjectionPoint<B>, AnnotationMetadataProvider, AnnotatedElement, ArgumentCoercible<T> {

    /**
     * @return The name of the field
     */
    @Override
    String getName();

    /**
     * Resolves the underlying field. Note that this method will cause reflection
     * metadata to be initialized and should be avoided.
     *
     * @return The target field
     */
    @NextMajorVersion("Adjust Micronaut test to avoid this method")
    @Deprecated(since = "4")
    Field getField();

    /**
     * @return The required component type
     */
    Class<T> getType();

}
