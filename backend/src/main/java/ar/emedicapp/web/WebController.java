package ar.emedicapp.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {
    @GetMapping({"/", "/app", "/app/**"})
    public String application() {
        return "forward:/index.html";
    }
}
