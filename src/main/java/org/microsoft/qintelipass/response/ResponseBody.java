package org.microsoft.qintelipass.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Data
@ToString
@Builder
@AllArgsConstructor
public class ResponseBody<T> {
    private boolean success;
    private String message;
    private T payload;

    /** 简单场景：Key-Value 用户数据 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> data;

    /** 复杂场景：任意类型的聚合数据（仪表盘、列表等） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object rawData;

    public void setRawData(Object rawData) {
        this.rawData = rawData;
    }
}
