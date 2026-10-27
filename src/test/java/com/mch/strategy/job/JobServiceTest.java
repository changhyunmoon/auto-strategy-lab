package com.mch.strategy.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class JobServiceTest {

    @Test
    void stop_request_stops_start_and_update_then_clears_on_finish() {
        JobRepository repo = mock(JobRepository.class);
        Job job = Job.forTarget(Job.TYPE_TRAINSET_BUILD, 5);
        when(repo.findById(1L)).thenReturn(Optional.of(job));
        JobCancels cancels = new JobCancels();
        JobService service = new JobService(repo, cancels);

        service.update(1, 10, "행 읽기", null); // 요청 전에는 그대로 진행
        assertThat(job.getProgress()).isEqualTo(10);

        cancels.request(1);
        assertThatThrownBy(() -> service.update(1, 20, "정답 계산", null)).isInstanceOf(JobCancels.CancelledException.class);
        assertThatThrownBy(() -> service.start(1)).isInstanceOf(JobCancels.CancelledException.class); // 대기 중에 중지
        assertThat(job.getProgress()).isEqualTo(10);

        service.fail(1, "중지했습니다.");
        assertThat(cancels.isRequested(1)).isFalse();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }
}
