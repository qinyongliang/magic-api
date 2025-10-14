package org.ssssssss.magicapi.lsp.web;

import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.lsp.web.dto.DtoConverters;
import org.ssssssss.magicapi.lsp.web.dto.MagicFileInfo;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${magic-api.web:/}")
public class FileController {

    private final MagicResourceService service = MagicConfiguration.getMagicResourceService();

    @GetMapping("/file/get/{fileId}")
    public JsonBean<MagicFileInfo> get(@PathVariable String fileId) {
        if (service == null) {
            return new JsonBean<>((MagicFileInfo) null);
        }
        MagicEntity entity = service.file(fileId);
        return new JsonBean<>(DtoConverters.fromEntity(entity, service));
    }

    @DeleteMapping("/file/delete/{fileId}")
    public JsonBean<Boolean> delete(@PathVariable String fileId) {
        if (service == null) {
            return new JsonBean<>(false);
        }
        return new JsonBean<>(service.delete(fileId));
    }
}