package com.cloudchunk.core.quota.service;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.quota.entity.UserQuota;
import com.cloudchunk.core.quota.mapper.UserQuotaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock private UserQuotaMapper mapper;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        service = new QuotaService(mapper);
        UserQuota quota = new UserQuota();
        quota.setUserId(1L);
        quota.setTotalBytes(100L);
        quota.setUsedBytes(40L);
        quota.setReservedBytes(20L);
        lenient().when(mapper.selectById(1L)).thenReturn(quota);
    }

    @Test
    void reserveIsAtomicAndReportsCapacityFailure() {
        when(mapper.tryReserve(1L, 50L)).thenReturn(0);

        assertThatThrownBy(() -> service.tryReserve(1L, 50L))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode())
                        .isEqualTo(ErrorCode.QUOTA_EXCEEDED));
    }

    @Test
    void cannotReduceTotalBelowUsedAndReservedCapacity() {
        when(mapper.setTotalAtLeastCommitted(1L, 50L)).thenReturn(0);

        assertThatThrownBy(() -> service.allocateSpace(1L, 50L))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAMETER));
    }

    @Test
    void commitsExistingReservation() {
        when(mapper.commitReservation(1L, 20L)).thenReturn(1);

        service.commitReservation(1L, 20L);

        verify(mapper).commitReservation(1L, 20L);
    }
}
