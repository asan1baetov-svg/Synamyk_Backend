package synamyk.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import synamyk.entities.NewsArticle;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {
    List<NewsArticle> findByActiveTrueOrderByPublishedAtDesc();

    @Query(value = "SELECT n FROM NewsArticle n"
            + " WHERE (:search IS NULL OR :search = ''"
            + "   OR LOWER(n.title) LIKE LOWER(CONCAT('%',:search,'%'))"
            + "   OR LOWER(n.content) LIKE LOWER(CONCAT('%',:search,'%')))"
            + " AND (n.type = COALESCE(NULLIF(:type, ''), n.type))"
            + " AND (n.active = COALESCE(:active, n.active))"
            + " AND (n.publishedAt >= COALESCE(:dateFrom, n.publishedAt))"
            + " AND (n.publishedAt <= COALESCE(:dateTo, n.publishedAt))"
            + " ORDER BY n.publishedAt DESC",
            countQuery = "SELECT COUNT(n) FROM NewsArticle n"
            + " WHERE (:search IS NULL OR :search = ''"
            + "   OR LOWER(n.title) LIKE LOWER(CONCAT('%',:search,'%'))"
            + "   OR LOWER(n.content) LIKE LOWER(CONCAT('%',:search,'%')))"
            + " AND (n.type = COALESCE(NULLIF(:type, ''), n.type))"
            + " AND (n.active = COALESCE(:active, n.active))"
            + " AND (n.publishedAt >= COALESCE(:dateFrom, n.publishedAt))"
            + " AND (n.publishedAt <= COALESCE(:dateTo, n.publishedAt))")
    Page<NewsArticle> findAllByFilters(
            @Param("search") String search,
            @Param("type") String type,
            @Param("active") Boolean active,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable);

    @Query("SELECT COUNT(n) FROM NewsArticle n WHERE n.createdAt >= :from")
    long countCreatedAfter(@Param("from") LocalDateTime from);
}
