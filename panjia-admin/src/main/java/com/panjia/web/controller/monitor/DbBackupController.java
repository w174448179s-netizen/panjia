package com.panjia.web.controller.monitor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com.panjia.common.annotation.Log;
import com.panjia.common.core.controller.BaseController;
import com.panjia.common.core.domain.AjaxResult;
import com.panjia.common.core.page.TableDataInfo;
import com.panjia.common.enums.BusinessType;
import com.panjia.common.utils.file.FileUtils;
import com.panjia.framework.backup.DbBackupService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 数据库备份恢复控制器
 */
@Controller
@RequestMapping("/monitor/backup")
public class DbBackupController extends BaseController
{
    private final String prefix = "monitor/backup";

    @Autowired
    private DbBackupService backupService;

    @RequiresPermissions("monitor:backup:view")
    @GetMapping()
    public String backup()
    {
        return prefix + "/backup";
    }

    @RequiresPermissions("monitor:backup:list")
    @PostMapping("/list")
    @ResponseBody
    public TableDataInfo list()
    {
        startPage();
        List<Map<String, Object>> list = backupService.listBackups();
        return getDataTable(list);
    }

    @RequiresPermissions("monitor:backup:create")
    @Log(title = "备份恢复", businessType = BusinessType.INSERT)
    @PostMapping("/create")
    @ResponseBody
    public AjaxResult create()
    {
        try
        {
            Map<String, Object> info = backupService.createBackup();
            AjaxResult r = success(info);
            r.put(AjaxResult.MSG_TAG, "备份成功");
            return r;
        }
        catch (Exception e)
        {
            return error("备份失败：" + e.getMessage());
        }
    }

    @RequiresPermissions("monitor:backup:download")
    @GetMapping("/download")
    public void download(@RequestParam("fileName") String fileName, HttpServletResponse response) throws IOException
    {
        Path p = backupService.findBackupFile(fileName);
        if (p == null)
        {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            try (OutputStream os = response.getOutputStream())
            {
                os.write("备份文件不存在".getBytes("UTF-8"));
            }
            return;
        }
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        FileUtils.setAttachmentResponseHeader(response, fileName);
        try (OutputStream os = response.getOutputStream())
        {
            Files.copy(p, os);
        }
    }

    @RequiresPermissions("monitor:backup:remove")
    @Log(title = "备份恢复", businessType = BusinessType.DELETE)
    @PostMapping("/remove")
    @ResponseBody
    public AjaxResult remove(String fileName)
    {
        if (backupService.deleteBackup(fileName)) return success();
        return error("删除失败：文件不存在或无权限");
    }

    /** 用指定的历史备份恢复数据库 */
    @RequiresPermissions("monitor:backup:restore")
    @Log(title = "备份恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/restore")
    @ResponseBody
    public AjaxResult restore(String fileName)
    {
        try
        {
            String msg = backupService.restoreFromBackup(fileName);
            AjaxResult r = success(msg);
            r.put(AjaxResult.MSG_TAG, "恢复成功");
            return r;
        }
        catch (Exception e)
        {
            return error("恢复失败：" + e.getMessage());
        }
    }

    /** 上传 zip 备份包并恢复数据库（换机迁移） */
    @RequiresPermissions("monitor:backup:restore")
    @Log(title = "备份恢复", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/restoreUpload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public AjaxResult restoreUpload(@RequestParam("file") MultipartFile file)
    {
        try
        {
            String msg = backupService.restoreFromUpload(file);
            AjaxResult r = success(msg);
            r.put(AjaxResult.MSG_TAG, "恢复成功");
            return r;
        }
        catch (Exception e)
        {
            return error("恢复失败：" + e.getMessage());
        }
    }
}
