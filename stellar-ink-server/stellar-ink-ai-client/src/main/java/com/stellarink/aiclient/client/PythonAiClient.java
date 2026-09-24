package com.stellarink.aiclient.client;

import com.stellarink.aiclient.constant.AiContractPaths;
import com.stellarink.aiclient.dto.IndexJobDTO;
import com.stellarink.aiclient.dto.IndexRebuildRequestDTO;
import com.stellarink.aiclient.dto.QaAnswerDTO;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestResultDTO;
import com.stellarink.aiclient.fallback.PythonAiClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Java 鈫?Python 鐨勫唴閮ㄥ鎴风濂戠害锛團eign锛夈€? *
 * <p>杩欐槸**鍐呯綉鍗忚**锛氳矾寰勪笌 8200 绔彛閮戒笉缁忕綉鍏筹紝娴忚鍣ㄦ棤娉曠洿杈撅紙绾㈢嚎 搂7.2锛夈€? * 瀵瑰鎺ュ彛鏄?ai-service 鐨?{@code /ai/**}锛屼袱鑰呬笉瑕佹贩涓轰竴璋堛€? *
 * <p>M0 鍙喕缁撳绾︼細鏂规硶绛惧悕涓?DTO 宸插畾锛岀湡姝ｇ殑璋冪敤銆佺鍚嶅ご娉ㄥ叆锛圡1锛夌敱 ai-service 瑁呴厤銆? * URL 璧伴厤缃」 {@code ai.python.base-url}锛堥粯璁?{@code http://127.0.0.1:8200}锛夛紝
 * 涓嶆敞鍐?Nacos锛歅ython 涓嶅弬涓?Java 鏈嶅姟鍙戠幇銆? */
@FeignClient(
        name = "python-ai",
        url = "${ai.python.base-url:http://127.0.0.1:8200}",
        fallbackFactory = PythonAiClientFallbackFactory.class)
public interface PythonAiClient {

    /** Python 渚ф帰娲伙紙鍘熷 JSON锛屼究浜?ai-service 鍒ゅ畾闄嶇骇鍘熷洜锛夈€?*/
    @GetMapping(AiContractPaths.HEALTH)
    Map<String, Object> health();

    /**
     * 娴佸紡闂瓟銆?     *
     * <p>杩斿洖绫诲瀷鏆傚畾 {@code Object}锛歁1 鎵撻€?SSE 鏃跺啀鎹㈡垚鍏蜂綋鐨勪簨浠舵祦绫诲瀷
     * 锛圫pring 渚х敤 {@code ResponseBodyEmitter} / WebClient 娴侊紝鐢?ai-service 鍐冲畾锛?     * 瀹㈡埛绔笉鎻愬墠缁戝畾鏌愮浼犺緭瀹炵幇锛夈€?     */
    @PostMapping(
            value = AiContractPaths.QA_STREAM,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Object qaStream(@RequestBody QaStreamRequestDTO request);

    /** 鍐欎綔寤鸿锛堣崏绋垮彧鍦ㄦ湰娆¤姹傚唴浣跨敤锛夈€?*/
    @PostMapping(
            value = AiContractPaths.WRITING_SUGGEST,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    WritingSuggestResultDTO writingSuggest(@RequestBody WritingSuggestRequestDTO request);

    /** 瑙﹀彂绱㈠紩閲嶅缓浠诲姟锛圓DMIN锛夈€?*/
    @PostMapping(
            value = AiContractPaths.INDEX_REBUILD,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    IndexJobDTO rebuildIndex(@RequestBody IndexRebuildRequestDTO request);

    /** 鏌ヨ绱㈠紩浠诲姟鐘舵€侊紙ADMIN锛夈€?*/
    @GetMapping(value = AiContractPaths.INDEX_JOB, produces = MediaType.APPLICATION_JSON_VALUE)
    IndexJobDTO indexJob(@PathVariable("id") String jobId);
}
