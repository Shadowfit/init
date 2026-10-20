package com.shadowfit.model.exercise;

import java.io.Serializable;
import java.util.Objects;

/** {@link SessionSet} 의 복합 PK — (session_id, set_no). {@code @IdClass} 규약대로 필드 이름이 엔티티와 같다. */
public class SessionSetId implements Serializable {

    private Long session;
    private Integer setNo;

    protected SessionSetId() {
    }

    public SessionSetId(Long session, Integer setNo) {
        this.session = session;
        this.setNo = setNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionSetId that)) return false;
        return Objects.equals(session, that.session) && Objects.equals(setNo, that.setNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(session, setNo);
    }
}
