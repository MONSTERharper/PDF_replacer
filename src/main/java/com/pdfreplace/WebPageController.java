package com.pdfreplace;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebPageController {
    @GetMapping({"/", "/about", "/contact", "/replace"})
    public String spaEntry() {
        return "forward:/app/index.html";
    }
}
