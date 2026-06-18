package com.cayleywcs.dict;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import com.cayleywcs.dict.entity.DictItemEntity;
import com.cayleywcs.dict.entity.DictTypeEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Dict")
public class DictController {
    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    @PostMapping("/dict/type/list")
    @Operation(summary = "字典类型列表")
    public ApiResponse<List<DictTypeEntity>> listTypes() {
        return ApiResponse.success(dictService.listTypes());
    }

    @PostMapping("/dict/type/create")
    public ApiResponse<DictTypeEntity> createType(@RequestBody DictTypeEntity entity) {
        return ApiResponse.success(dictService.createType(entity));
    }

    @PostMapping("/dict/type/update")
    public ApiResponse<DictTypeEntity> updateType(@RequestBody DictTypeEntity entity) {
        return ApiResponse.success(dictService.updateType(entity));
    }

    @PostMapping("/dict/type/delete")
    public ApiResponse<Boolean> deleteType(@RequestBody IdRequest request) {
        return ApiResponse.success(dictService.deleteType(request.id()));
    }

    @PostMapping("/dict/item/list-by-type")
    @Operation(summary = "按类型查字典项")
    public ApiResponse<List<DictItemEntity>> listItems(@RequestBody DictTypeQuery query) {
        return ApiResponse.success(dictService.listItems(query.typeCode()));
    }

    @PostMapping("/dict/item/create")
    public ApiResponse<DictItemEntity> createItem(@RequestBody DictItemEntity entity) {
        return ApiResponse.success(dictService.createItem(entity));
    }

    @PostMapping("/dict/item/update")
    public ApiResponse<DictItemEntity> updateItem(@RequestBody DictItemEntity entity) {
        return ApiResponse.success(dictService.updateItem(entity));
    }

    @PostMapping("/dict/item/delete")
    public ApiResponse<Boolean> deleteItem(@RequestBody IdRequest request) {
        return ApiResponse.success(dictService.deleteItem(request.id()));
    }

    public record DictTypeQuery(String typeCode) {
    }
}
