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
package io.micronaut.inject.factory.beanwithfactory;

import io.micronaut.context.event.BeanInitializedEventListener;
import io.micronaut.context.event.BeanInitializingEvent;

import javax.inject.Singleton;

@Singleton
public class MyListener implements BeanInitializedEventListener<BFactory> {

    @Override
    public BFactory onInitialized(BeanInitializingEvent<BFactory> event) {
        BFactory bean = event.getBean();
        assert bean.getMethodInjected() != null;
        assert bean.getFieldA() != null;
        assert bean.getAnotherField() != null;
        assert bean.a != null;
        assert !bean.postConstructCalled;
        assert !bean.getCalled;
        bean.name = "changed";
        return bean;
    }
}
