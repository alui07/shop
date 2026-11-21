package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionProperties;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;

@ExtendWith(OutputCaptureExtension.class)
class AsyncConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(AsyncConfiguration.class, SchedulingConfiguration.class, Worker.class);

    @Configuration(proxyBeanMethods = false)
    static class Worker {

        @Bean
        Task task() {
            return new Task();
        }
    }

    static class Task {

        @Async
        CompletableFuture<String> threadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }

    @Test
    void asyncRunsOnTheTaskExecutorWithJobsOff() {
        runner.withPropertyValues("shop.jobs.enabled=false")
                .run(context -> assertThat(
                                context.getBean(Task.class).threadName().get())
                        .startsWith("task-")
                        .isNotEqualTo(Thread.currentThread().getName()));
    }

    @Test
    void asyncRunsOnTheTaskExecutorWithJobsOn() {
        runner.withPropertyValues("shop.jobs.enabled=true").run(context -> {
            assertThat(context).hasBean("taskScheduler");
            assertThat(context.getBeansOfType(Executor.class)).hasSizeGreaterThan(1);
            assertThat(context.getBean(Task.class).threadName().get())
                    .startsWith("task-")
                    .isNotEqualTo(Thread.currentThread().getName());
        });
    }

    @Test
    void theExecutorWaitsForRunningListenersOnShutdownAsApplicationYamlSays() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            var properties = context.getBean(TaskExecutionProperties.class);
            assertThat(properties.getShutdown().isAwaitTermination()).isTrue();
            assertThat(properties.getShutdown().getAwaitTerminationPeriod()).hasSeconds(15);
            assertThat(properties.getPool().getMaxSize()).isEqualTo(8);
        });
    }

    @Test
    void anUncaughtAsyncFailureIsLoggedWithItsClassAndMessageButNoStackTrace(CapturedOutput output) throws Exception {
        var handler = new AsyncConfiguration().getAsyncUncaughtExceptionHandler();

        handler.handleUncaughtException(
                new ListenerFailedException("Confirmation for order 1 failed with MailSendException"),
                Task.class.getDeclaredMethod("threadName"));

        assertThat(output.getAll())
                .contains("Asynchronous threadName failed with ListenerFailedException")
                .contains("Confirmation for order 1 failed with MailSendException")
                .doesNotContain("\tat ");
    }
}
