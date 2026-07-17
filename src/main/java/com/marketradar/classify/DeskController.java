package com.marketradar.classify;

import com.marketradar.domain.Department;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/** Ops console desk hub: which department received which routed stories, and why. */
@Controller
public class DeskController {

    private final DeskService desks;

    public DeskController(DeskService desks) {
        this.desks = desks;
    }

    @GetMapping("/desks")
    public String desks(Model model) {
        model.addAttribute("desks", desks.overview());
        return "desks";
    }

    @GetMapping("/desks/{dept}")
    public String desk(@PathVariable String dept, Model model) {
        Department department;
        try {
            department = Department.valueOf(dept.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown desk: " + dept);
        }
        model.addAttribute("dept", department);
        model.addAttribute("items", desks.deskItems(department));
        return "desk-detail";
    }
}
