package com.stellarink.content.post.mapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostViewMapperTest {

    private final PostViewMapper mapper = mock(PostViewMapper.class, CALLS_REAL_METHODS);

    @Test
    void claimsExistingGateOnlyWhenDateAdvances() {
        when(mapper.advanceToToday(7L)).thenReturn(1);

        assertThat(mapper.claimToday(7L)).isTrue();
        verify(mapper, never()).insertToday(7L);
    }

    @Test
    void createsMissingGateAndRejectsSameDayRepeat() {
        when(mapper.advanceToToday(7L)).thenReturn(0);
        when(mapper.insertToday(7L)).thenReturn(1, 0);

        assertThat(mapper.claimToday(7L)).isTrue();
        assertThat(mapper.claimToday(7L)).isFalse();
    }
}
