package com.clawbotforall.diagnostics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import java.lang.reflect.Constructor;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OomDiagnosticsSchedulerTest {

  @Mock
  InstanceAggregateMapper instanceAggregateMapper;

  @Mock
  OomDiagnosticsService diagnosticsService;

  @Test
  void marksTheSpringConstructorExplicitlyWhenTestConstructorAlsoExists() throws Exception {
    Constructor<OomDiagnosticsScheduler> constructor = OomDiagnosticsScheduler.class.getConstructor(
        InstanceAggregateMapper.class,
        OomDiagnosticsService.class,
        ClawbotProperties.class
    );

    org.assertj.core.api.Assertions.assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
  }

  @Test
  void doesNotQueryInstancesWhenDiagnosticsAreDisabled() {
    OomDiagnosticsScheduler scheduler = scheduler(false);

    scheduler.collectRuntimeDiagnostics();

    verifyNoInteractions(instanceAggregateMapper, diagnosticsService);
  }

  @Test
  void collectsDiagnosticsOnlyForConfiguredInstancesWhenEnabled() {
    InstanceEntity first = instance("instance-1");
    InstanceEntity second = instance("instance-2");
    InstanceEntity outsideScope = instance("instance-3");
    when(instanceAggregateMapper.listRuntimeActive()).thenReturn(List.of(first, second, outsideScope));
    OomDiagnosticsScheduler scheduler = scheduler(true, List.of("instance-1", "instance-2"));

    scheduler.collectRuntimeDiagnostics();

    verify(instanceAggregateMapper).listRuntimeActive();
    verify(diagnosticsService).collect(first);
    verify(diagnosticsService).collect(second);
    verifyNoMoreInteractions(instanceAggregateMapper, diagnosticsService);
  }

  @Test
  void continuesCollectingFollowingInstancesWhenOneCollectionFails() {
    InstanceEntity first = instance("instance-1");
    InstanceEntity second = instance("instance-2");
    when(instanceAggregateMapper.listRuntimeActive()).thenReturn(List.of(first, second));
    doThrow(new IllegalStateException("diagnostics failed")).when(diagnosticsService).collect(first);
    OomDiagnosticsScheduler scheduler = scheduler(true, List.of("instance-1", "instance-2"));

    scheduler.collectRuntimeDiagnostics();

    verify(diagnosticsService).collect(first);
    verify(diagnosticsService).collect(second);
  }

  @Test
  void swallowsInstanceLookupFailures() {
    when(instanceAggregateMapper.listRuntimeActive()).thenThrow(new IllegalStateException("mapper failed"));
    OomDiagnosticsScheduler scheduler = scheduler(true);

    assertThatCode(scheduler::collectRuntimeDiagnostics).doesNotThrowAnyException();

    verify(instanceAggregateMapper).listRuntimeActive();
    verifyNoInteractions(diagnosticsService);
  }

  private OomDiagnosticsScheduler scheduler(boolean enabled) {
    return scheduler(enabled, List.of());
  }

  private OomDiagnosticsScheduler scheduler(boolean enabled, List<String> instanceIds) {
    return new OomDiagnosticsScheduler(
        instanceAggregateMapper,
        diagnosticsService,
        new ClawbotProperties(
            null,
            null,
            null,
            null,
            new ClawbotProperties.OomDiagnostics(
                enabled,
                30_000,
                7,
                256,
                30,
                instanceIds,
                5,
                12,
                1,
                600_000
            )
        ),
        Runnable::run
    );
  }

  private static InstanceEntity instance(String id) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId(id);
    return instance;
  }
}
