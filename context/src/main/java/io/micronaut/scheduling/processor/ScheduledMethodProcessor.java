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
package io.micronaut.scheduling.processor;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.context.processor.ExecutableMethodProcessor;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.ScheduledExecutorTaskScheduler;
import io.micronaut.scheduling.TaskExceptionHandler;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.scheduling.exceptions.SchedulerConfigurationException;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * A {@link ExecutableMethodProcessor} for the {@link Scheduled} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
public class ScheduledMethodProcessor implements ExecutableMethodProcessor<Scheduled>, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(TaskScheduler.class);
    private static final String MEMBER_FIXED_RATE = "fixedRate";
    private static final String MEMBER_INITIAL_DELAY = "initialDelay";
    private static final String MEMBER_CRON = "cron";
    private static final String MEMBER_ZONE_ID = "zoneId";
    private static final String MEMBER_FIXED_DELAY = "fixedDelay";
    private static final String MEMBER_SCHEDULER = "scheduler";

    private final BeanContext beanContext;
    private final ConversionService conversionService;
    private final Queue<ScheduledFuture<?>> scheduledTasks = new ConcurrentLinkedDeque<>();
    private final TaskExceptionHandler<?, ?> taskExceptionHandler;

    /**
     * @param beanContext       The bean context for DI of beans annotated with @Inject
     * @param conversionService To convert one type to another
     * @param taskExceptionHandler The default task exception handler
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public ScheduledMethodProcessor(BeanContext beanContext, Optional<ConversionService> conversionService, TaskExceptionHandler<?, ?> taskExceptionHandler) {
        this.beanContext = beanContext;
        this.conversionService = conversionService.orElse(ConversionService.SHARED);
        this.taskExceptionHandler = taskExceptionHandler;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void process(BeanDefinition<?> beanDefinition, ExecutableMethod<?, ?> method) {
        if (!(beanContext instanceof ApplicationContext)) {
            return;
        }

        List<AnnotationValue<Scheduled>> scheduledAnnotations = method.getAnnotationValuesByType(Scheduled.class);
        for (AnnotationValue<Scheduled> scheduledAnnotation : scheduledAnnotations) {
            String fixedRate = scheduledAnnotation.stringValue(MEMBER_FIXED_RATE).orElse(null);

            String initialDelayStr = scheduledAnnotation.stringValue(MEMBER_INITIAL_DELAY).orElse(null);
            Duration initialDelay = null;
            if (StringUtils.hasText(initialDelayStr)) {
                initialDelay = conversionService.convert(initialDelayStr, Duration.class).orElseThrow(() ->
                    new SchedulerConfigurationException(method, "Invalid initial delay definition: " + initialDelayStr)
                );
            }

            String scheduler = scheduledAnnotation.stringValue(MEMBER_SCHEDULER).orElse(TaskExecutors.SCHEDULED);
            Optional<TaskScheduler> optionalTaskScheduler = beanContext
                    .findBean(TaskScheduler.class, Qualifiers.byName(scheduler));

            if (optionalTaskScheduler.isEmpty()) {
                optionalTaskScheduler = beanContext.findBean(ExecutorService.class, Qualifiers.byName(scheduler))
                        .filter(ScheduledExecutorService.class::isInstance)
                        .map(ScheduledExecutorTaskScheduler::new);
            }

            TaskScheduler taskScheduler = optionalTaskScheduler.orElseThrow(() -> new SchedulerConfigurationException(method, "No scheduler of type TaskScheduler configured for name: " + scheduler));

            Runnable task = () -> {
                io.micronaut.context.Qualifier<Object> qualifer = beanDefinition
                    .getAnnotationTypeByStereotype(AnnotationUtil.QUALIFIER)
                    .map(type -> Qualifiers.byAnnotation(beanDefinition, type))
                    .orElse(null);

                Class<Object> beanType = (Class<Object>) beanDefinition.getBeanType();
                Object bean = null;
                try {
                    bean = beanContext.getBean(beanType, qualifer);
                    if (method.getArguments().length == 0) {
                        ((ExecutableMethod) method).invoke(bean);
                    }
                } catch (Throwable e) {
                    io.micronaut.context.Qualifier<TaskExceptionHandler> qualifier = Qualifiers.byTypeArguments(beanType, e.getClass());
                    Collection<BeanDefinition<TaskExceptionHandler>> definitions = beanContext.getBeanDefinitions(TaskExceptionHandler.class, qualifier);
                    Optional<BeanDefinition<TaskExceptionHandler>> mostSpecific = definitions.stream().filter(def -> {
                        List<Argument<?>> typeArguments = def.getTypeArguments(TaskExceptionHandler.class);
                        if (typeArguments.size() == 2) {
                            return typeArguments.get(0).getType() == beanType && typeArguments.get(1).getType() == e.getClass();
                        }
                        return false;
                    }).findFirst();

                    TaskExceptionHandler finalHandler = mostSpecific.map(bd -> beanContext.getBean(bd.getBeanType(), qualifier)).orElse(this.taskExceptionHandler);
                    finalHandler.handle(bean, e);
                }
            };

            String cronExpr = scheduledAnnotation.stringValue(MEMBER_CRON).orElse(null);
            String zoneIdStr = scheduledAnnotation.stringValue(MEMBER_ZONE_ID).orElse(null);
            String fixedDelay = scheduledAnnotation.stringValue(MEMBER_FIXED_DELAY).orElse(null);

            if (StringUtils.isNotEmpty(cronExpr)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Scheduling cron task [{}] for method: {}", cronExpr, method);
                }

                ScheduledFuture<?> scheduledFuture = taskScheduler.schedule(cronExpr, zoneIdStr, task);
                scheduledTasks.add(scheduledFuture);
            } else if (StringUtils.isNotEmpty(fixedRate)) {
                Optional<Duration> converted = conversionService.convert(fixedRate, Duration.class);
                Duration duration = converted.orElseThrow(() ->
                    new SchedulerConfigurationException(method, "Invalid fixed rate definition: " + fixedRate)
                );

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Scheduling fixed rate task [{}] for method: {}", duration, method);
                }

                ScheduledFuture<?> scheduledFuture = taskScheduler.scheduleAtFixedRate(initialDelay, duration, task);
                scheduledTasks.add(scheduledFuture);
            } else if (StringUtils.isNotEmpty(fixedDelay)) {
                Optional<Duration> converted = conversionService.convert(fixedDelay, Duration.class);
                Duration duration = converted.orElseThrow(() ->
                    new SchedulerConfigurationException(method, "Invalid fixed delay definition: " + fixedDelay)
                );


                if (LOG.isDebugEnabled()) {
                    LOG.debug("Scheduling fixed delay task [{}] for method: {}", duration, method);
                }

                ScheduledFuture<?> scheduledFuture = taskScheduler.scheduleWithFixedDelay(initialDelay, duration, task);
                scheduledTasks.add(scheduledFuture);
            } else if (initialDelay != null) {
                ScheduledFuture<?> scheduledFuture = taskScheduler.schedule(initialDelay, task);

                scheduledTasks.add(scheduledFuture);
            } else {
                throw new SchedulerConfigurationException(method, "Failed to schedule task. Invalid definition");
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        for (ScheduledFuture<?> scheduledTask : scheduledTasks) {
            if (!scheduledTask.isCancelled()) {
                scheduledTask.cancel(false);
            }
        }
    }
}
