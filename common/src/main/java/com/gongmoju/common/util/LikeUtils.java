package com.gongmoju.common.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 부분 일치(LIKE) 검색 패턴. 사용자 입력의 %, _ 가 와일드카드로 해석되지 않도록 이스케이프한다
 * <p>
 * 사용: {@code cb.like(root.get("name"), LikeUtils.contains(keyword), LikeUtils.ESCAPE_CHAR)}
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LikeUtils {

    /** LIKE ... ESCAPE 에 함께 넘길 이스케이프 문자. 지정하지 않으면 Hibernate가 escape ''(없음)로 보낸다 */
    public static final char ESCAPE_CHAR = '\\';

    /**
     * 앞뒤 공백을 지우고 \ % _ 를 글자로 바꾼 뒤 앞뒤에 % 를 붙인다. keyword는 호출 측에서 null, 공백을 걸러서 넘긴다
     *
     * @param keyword 사용자 검색어
     * @return LIKE 패턴 (예: {@code %검색어%})
     */
    // \ 를 먼저 바꿔야 한다. 나중에 바꾸면 % _ 앞에 붙인 \ 까지 다시 바뀐다
    public static String contains(String keyword) {
        String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
