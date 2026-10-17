package com.cab;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.UUID;
@SpringBootApplication
public class CabApp {
  public static void main(String[] a){ SpringApplication.run(CabApp.class,a); }
  // Admin account: set ADMIN_NAME, ADMIN_EMAIL and ADMIN_PASSWORD to choose your own.
  // No admin yet: one is created. Admin exists and ADMIN_PASSWORD is set: that admin is updated to these values.
  @Bean CommandLineRunner seed(UserRepo r,PasswordEncoder pe,@Value("${ADMIN_NAME:Admin}") String name,
      @Value("${ADMIN_EMAIL:admin@cab.local}") String email,@Value("${ADMIN_PASSWORD:}") String p){
    return x->{
      String em=email.trim().toLowerCase(), nm=name.trim();
      if(nm.isEmpty()||nm.length()>60) throw new IllegalStateException("ADMIN_NAME must be 1 to 60 characters");
      if(!em.matches("^[^@\\s]{1,64}@[^@\\s]{1,190}$")) throw new IllegalStateException("ADMIN_EMAIL is not a valid email");
      if(!p.isBlank()&&p.length()<8) throw new IllegalStateException("ADMIN_PASSWORD must be at least 8 characters");
      User found=r.findAll().stream().filter(u->"ADMIN".equals(u.role)).findFirst().orElse(null);
      if(found==null){
        if(r.findByEmail(em).isPresent()) throw new IllegalStateException("ADMIN_EMAIL is already used by another account");
        boolean random=p.isBlank(); String pw=random?UUID.randomUUID().toString().substring(0,12):p;
        User a=new User(); a.role="ADMIN"; a.name=nm; a.email=em; a.password=pe.encode(pw); r.save(a);
        System.out.println("=== ADMIN LOGIN: "+em+" / "+(random?pw:"(the ADMIN_PASSWORD you set)")+" ===");
      } else if(!p.isBlank()){
        final Long id=found.id;
        if(r.findByEmail(em).filter(u->!u.id.equals(id)).isPresent()) throw new IllegalStateException("ADMIN_EMAIL is already used by another account");
        found.name=nm; found.email=em; found.password=pe.encode(p); found.active=true; found.tokenVersion++; r.save(found);
        System.out.println("=== ADMIN UPDATED: "+em+" (password from ADMIN_PASSWORD) ===");
      }
    };
  }
}
