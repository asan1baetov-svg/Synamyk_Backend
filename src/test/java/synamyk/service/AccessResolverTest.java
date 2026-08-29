package synamyk.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import synamyk.entities.SubTest;
import synamyk.repo.UserSubTestAccessRepository;
import synamyk.repo.UserTestAccessRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessResolverTest {

    @Mock UserTestAccessRepository userTestAccessRepo;
    @Mock UserSubTestAccessRepository userSubTestAccessRepo;

    @InjectMocks AccessResolver resolver;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);

    // ===== isFreeNow =====

    @Test
    void isFreeNow_noBounds_isNotFree() {
        assertThat(AccessResolver.isFreeNow(null, null, NOW)).isFalse();
    }

    @Test
    void isFreeNow_onlyUntil_freeBeforeIt() {
        assertThat(AccessResolver.isFreeNow(null, NOW.plusDays(3), NOW)).isTrue();
        assertThat(AccessResolver.isFreeNow(null, NOW.minusDays(1), NOW)).isFalse();
    }

    @Test
    void isFreeNow_onlyFrom_freeFromItOnward() {
        assertThat(AccessResolver.isFreeNow(NOW.minusDays(1), null, NOW)).isTrue();
        assertThat(AccessResolver.isFreeNow(NOW.plusDays(1), null, NOW)).isFalse();
    }

    @Test
    void isFreeNow_range_freeOnlyInside() {
        assertThat(AccessResolver.isFreeNow(NOW.minusDays(1), NOW.plusDays(1), NOW)).isTrue();
    }

    @Test
    void isFreeNow_range_outside_isNotFree() {
        assertThat(AccessResolver.isFreeNow(NOW.plusDays(1), NOW.plusDays(2), NOW)).isFalse();
        assertThat(AccessResolver.isFreeNow(NOW.minusDays(2), NOW.minusDays(1), NOW)).isFalse();
    }

    @Test
    void isFreeNow_untilBoundaryIsExclusive() {
        assertThat(AccessResolver.isFreeNow(null, NOW, NOW)).isFalse();
    }

    // ===== hasSubTestAccess =====

    private SubTest subTest(boolean paid) {
        synamyk.entities.Test t = new synamyk.entities.Test();
        t.setId(1L);
        SubTest st = new SubTest();
        st.setId(10L);
        st.setTest(t);
        st.setIsPaid(paid);
        return st;
    }

    @Test
    void freeSubTest_alwaysAccessible() {
        assertThat(resolver.hasSubTestAccess(7L, subTest(false), NOW)).isTrue();
    }

    @Test
    void paidSubTest_testFreeWindowOpen_accessible() {
        SubTest st = subTest(true);
        st.getTest().setFreeUntil(NOW.plusDays(2));
        assertThat(resolver.hasSubTestAccess(7L, st, NOW)).isTrue();
    }

    @Test
    void paidSubTest_subTestFreeWindowOpen_accessible() {
        SubTest st = subTest(true);
        st.setFreeFrom(NOW.minusDays(1));
        st.setFreeUntil(NOW.plusDays(1));
        assertThat(resolver.hasSubTestAccess(7L, st, NOW)).isTrue();
    }

    @Test
    void paidSubTest_activeBundleAccess_accessible() {
        SubTest st = subTest(true);
        when(userTestAccessRepo.existsActiveAccess(7L, 1L, NOW)).thenReturn(true);
        assertThat(resolver.hasSubTestAccess(7L, st, NOW)).isTrue();
    }

    @Test
    void paidSubTest_activeSubTestAccess_accessible() {
        SubTest st = subTest(true);
        when(userTestAccessRepo.existsActiveAccess(7L, 1L, NOW)).thenReturn(false);
        when(userSubTestAccessRepo.existsActiveAccess(7L, 10L, NOW)).thenReturn(true);
        assertThat(resolver.hasSubTestAccess(7L, st, NOW)).isTrue();
    }

    @Test
    void paidSubTest_noGrantNoWindow_notAccessible() {
        SubTest st = subTest(true);
        when(userTestAccessRepo.existsActiveAccess(7L, 1L, NOW)).thenReturn(false);
        when(userSubTestAccessRepo.existsActiveAccess(7L, 10L, NOW)).thenReturn(false);
        assertThat(resolver.hasSubTestAccess(7L, st, NOW)).isFalse();
    }

    @Test
    void paidSubTest_expiredWindow_notAccessible() {
        SubTest st = subTest(true);
        st.getTest().setFreeUntil(NOW.minusDays(1));
        lenient().when(userTestAccessRepo.existsActiveAccess(7L, 1L, NOW)).thenReturn(false);
        lenient().when(userSubTestAccessRepo.existsActiveAccess(7L, 10L, NOW)).thenReturn(false);
        assertThat(resolver.hasSubTestAccess(7L, st, NOW)).isFalse();
    }

    @Test
    void freeUntilBoundary_picksNearestEnd() {
        SubTest st = subTest(true);
        st.getTest().setFreeUntil(NOW.plusDays(10));
        st.setFreeFrom(NOW.minusDays(1));
        st.setFreeUntil(NOW.plusDays(3));
        assertThat(resolver.freeUntilBoundary(st, NOW)).isEqualTo(NOW.plusDays(3));
    }
}
