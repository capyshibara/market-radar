package com.marketradar.prompt;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Batch 12: ops console /prompts — XEM + SỬA prompt của từng stage AI. Sửa lưu DB, áp dụng
 * runtime (không cần khởi động lại). Có nút "Khôi phục mặc định" cho từng prompt.
 */
@Controller
public class PromptController {

    private final PromptService prompts;

    public PromptController(PromptService prompts) {
        this.prompts = prompts;
    }

    @GetMapping("/prompts")
    public String list(Model model) {
        model.addAttribute("rows", prompts.rows());
        return "prompts";
    }

    @PostMapping("/prompts/save")
    public String save(@RequestParam String key,
                       @RequestParam String body,
                       @RequestParam(defaultValue = "ops") String reviewerName,
                       RedirectAttributes redirect) {
        PromptKey k;
        try { k = PromptKey.valueOf(key); }
        catch (IllegalArgumentException e) { redirect.addFlashAttribute("msg", "Prompt key không hợp lệ: " + key); return "redirect:/prompts"; }
        if (body == null || body.isBlank()) {
            redirect.addFlashAttribute("msg", "Prompt rỗng — không lưu. Dùng 'Khôi phục mặc định' nếu muốn về bản gốc.");
            return "redirect:/prompts";
        }
        prompts.save(k, body.strip(), reviewerName);
        redirect.addFlashAttribute("msg", "Đã lưu prompt '" + k.label + "'. Áp dụng ngay ở lần chạy AI kế tiếp.");
        return "redirect:/prompts";
    }

    @PostMapping("/prompts/reset")
    public String reset(@RequestParam String key, RedirectAttributes redirect) {
        try {
            PromptKey k = PromptKey.valueOf(key);
            prompts.reset(k);
            redirect.addFlashAttribute("msg", "Đã khôi phục prompt '" + k.label + "' về mặc định.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("msg", "Prompt key không hợp lệ: " + key);
        }
        return "redirect:/prompts";
    }
}
