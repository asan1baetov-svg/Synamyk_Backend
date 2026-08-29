package synamyk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import synamyk.entities.AnswerOption;

import java.util.List;

@Repository
public interface AnswerOptionRepository extends JpaRepository<AnswerOption, Long> {
    List<AnswerOption> findByQuestionIdOrderByOrderIndexAsc(Long questionId);

    /** Number of user answers that have this option selected (join table user_answer_selected_options). */
    @Query(value = "SELECT COUNT(*) FROM user_answer_selected_options WHERE answer_option_id = :optionId",
            nativeQuery = true)
    long countUserAnswerReferences(@Param("optionId") Long optionId);
}