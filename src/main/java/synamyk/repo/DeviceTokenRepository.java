package synamyk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import synamyk.entities.DeviceToken;
import synamyk.enums.DevicePlatform;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByUserId(Long userId);

    void deleteByToken(String token);

    @Modifying
    @Transactional
    @Query("DELETE FROM DeviceToken d WHERE d.token IN :tokens")
    void deleteByTokenIn(@Param("tokens") List<String> tokens);

    @Modifying
    @Transactional
    @Query("DELETE FROM DeviceToken d WHERE d.lastSeenAt < :cutoff")
    int deleteByLastSeenAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /** token + owner's interface language, for every token — used by ALL-audience broadcasts. */
    @Query("SELECT d.token AS token, u.language AS lang FROM DeviceToken d JOIN d.user u")
    List<TokenLangView> findAllTokensWithLang();

    @Query("SELECT d.token AS token, u.language AS lang FROM DeviceToken d JOIN d.user u WHERE u.id IN :userIds")
    List<TokenLangView> findTokensWithLangByUserIds(@Param("userIds") List<Long> userIds);

    @Query("SELECT DISTINCT d.user.id FROM DeviceToken d")
    List<Long> findDistinctUserIds();

    @Query("SELECT DISTINCT d.user.id FROM DeviceToken d WHERE d.platform = :platform")
    List<Long> findDistinctUserIdsByPlatform(@Param("platform") DevicePlatform platform);

    long countByPlatform(DevicePlatform platform);

    interface TokenLangView {
        String getToken();
        String getLang();
    }
}
