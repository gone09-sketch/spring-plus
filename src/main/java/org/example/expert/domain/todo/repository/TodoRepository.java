package org.example.expert.domain.todo.repository;

import org.example.expert.domain.todo.entity.Todo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

public interface TodoRepository extends JpaRepository<Todo, Long>, TodoCustomRepository {

    /**
     * value는 실제 화면에 보여줄 Todo 목록을 조회하는 쿼리입니다.
     * countQuery는 Page 응답 생성을 위해 조건에 맞는 전체 Todo 개수를 조회하는 쿼리입니다.
     * 각 값이 null이면 해당 조건은 검색에서 제외됩니다.
     *
     * @param weather 날씨 조건. null이면 날씨 조건 없이 조회합니다.
     * @param modifiedFrom 수정일 시작 조건. null이면 시작일 제한 없이 조회합니다.
     * @param modifiedTo 수정일 종료 조건. null이면 종료일 제한 없이 조회합니다.
     * @param pageable 페이지 번호와 페이지 크기 정보
     * @return 검색 조건에 맞는 Todo 페이지 결과
     */
    @Query(
            value = "SELECT t FROM Todo t " +
                    "LEFT JOIN FETCH t.user " +
                    "WHERE (:weather IS NULL OR t.weather = :weather) " +
                    "AND (:modifiedFrom IS NULL OR t.modifiedAt >= :modifiedFrom) " +
                    "AND (:modifiedTo IS NULL OR t.modifiedAt <= :modifiedTo) " +
                    "ORDER BY t.modifiedAt DESC",
            countQuery = "SELECT COUNT(t) FROM Todo t " +
                    "WHERE (:weather IS NULL OR t.weather = :weather) " +
                    "AND (:modifiedFrom IS NULL OR t.modifiedAt >= :modifiedFrom) " +
                    "AND (:modifiedTo IS NULL OR t.modifiedAt <= :modifiedTo)"
    )
    Page<Todo> searchTodos(
            @Param("weather") String weather,
            @Param("modifiedFrom") LocalDateTime modifiedFrom,
            @Param("modifiedTo") LocalDateTime modifiedTo,
            Pageable pageable
    );
}
