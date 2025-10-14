package org.ssssssss.magicapi.lsp.web.dto;

public class MagicFileInfo {
    private String id;
    private String name;
    private String path;
    private String script;
    private String groupId;
    private String groupPath;
    private String type;
    private Long createTime;
    private Long updateTime;
    private String createBy;
    private String updateBy;
    private String method;
    private String requestMapping;
    private String description;
    private Boolean locked;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getScript() { return script; }
    public void setScript(String script) { this.script = script; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getGroupPath() { return groupPath; }
    public void setGroupPath(String groupPath) { this.groupPath = groupPath; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getCreateTime() { return createTime; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }
    public Long getUpdateTime() { return updateTime; }
    public void setUpdateTime(Long updateTime) { this.updateTime = updateTime; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getRequestMapping() { return requestMapping; }
    public void setRequestMapping(String requestMapping) { this.requestMapping = requestMapping; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getLocked() { return locked; }
    public void setLocked(Boolean locked) { this.locked = locked; }
}