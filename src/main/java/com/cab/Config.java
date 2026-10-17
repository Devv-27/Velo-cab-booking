package com.cab;
import io.jsonwebtoken.Claims; import io.jsonwebtoken.Jwts; import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain; import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest; import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message; import org.springframework.messaging.MessageChannel; import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration; import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor; import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint; import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component; import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.config.annotation.*;
import javax.crypto.SecretKey; import java.io.IOException; import java.security.SecureRandom; import java.time.Instant; import java.util.Date; import java.util.List;

@Component class Tokens {
  final SecretKey key;
  Tokens(@Value("${app.jwt.secret:}") String s){ byte[] k=new byte[48]; if(s.isBlank()) new SecureRandom().nextBytes(k); else k=s.getBytes(); key=Keys.hmacShaKeyFor(k); }
  String make(User u){ return Jwts.builder().subject(String.valueOf(u.id)).claim("v",u.tokenVersion).expiration(Date.from(Instant.now().plusSeconds(43200))).signWith(key).compact(); }
  Claims parse(String t){ return Jwts.parser().verifyWith(key).build().parseSignedClaims(t).getPayload(); }
  // role & active flag are always read from the DB, so bans and role changes take effect immediately
  User auth(String h,UserRepo r){ if(h==null||!h.startsWith("Bearer ")) return null;
    try{ Claims c=parse(h.substring(7)); User u=r.findById(Long.valueOf(c.getSubject())).orElse(null); Integer v=c.get("v",Integer.class);
      return u!=null&&u.active&&v!=null&&v==u.tokenVersion?u:null; }catch(Exception e){ return null; } }
  static UsernamePasswordAuthenticationToken tok(User u){ return new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority("ROLE_"+u.role))); }
}
@Component class JwtFilter extends OncePerRequestFilter {
  final Tokens t; final UserRepo r; JwtFilter(Tokens t,UserRepo r){ this.t=t; this.r=r; }
  @Override protected void doFilterInternal(HttpServletRequest q,HttpServletResponse s,FilterChain c) throws ServletException,IOException {
    User u=t.auth(q.getHeader("Authorization"),r); if(u!=null) SecurityContextHolder.getContext().setAuthentication(Tokens.tok(u)); c.doFilter(q,s); }
}
@Configuration @EnableMethodSecurity class SecConfig {
  @Bean PasswordEncoder pe(){ return new BCryptPasswordEncoder(12); }
  @Bean SecurityFilterChain chain(HttpSecurity h,JwtFilter f) throws Exception {
    h.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
     .authorizeHttpRequests(a->a.requestMatchers("/api/auth/**","/ws/**","/api/public/**").permitAll().requestMatchers("/api/**").authenticated().anyRequest().permitAll())
     .exceptionHandling(e->e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
     .addFilterBefore(f,UsernamePasswordAuthenticationFilter.class);
    return h.build(); }
}
@Configuration @EnableWebSocketMessageBroker class WsConf implements WebSocketMessageBrokerConfigurer {
  final Tokens t; final UserRepo r; WsConf(Tokens t,UserRepo r){ this.t=t; this.r=r; }
  public void configureMessageBroker(MessageBrokerRegistry m){ m.enableSimpleBroker("/topic"); }
  public void registerStompEndpoints(StompEndpointRegistry e){ e.addEndpoint("/ws"); }
  public void configureClientInboundChannel(ChannelRegistration c){ c.interceptors(new ChannelInterceptor(){
    public Message<?> preSend(Message<?> m,MessageChannel ch){
      StompHeaderAccessor a=MessageHeaderAccessor.getAccessor(m,StompHeaderAccessor.class);
      if(a==null||a.getCommand()==null) return m;
      switch(a.getCommand()){
        case CONNECT -> { User u=t.auth(a.getFirstNativeHeader("Authorization"),r); if(u==null) throw new MessageDeliveryException("Unauthorized"); a.setUser(Tokens.tok(u)); }
        case SUBSCRIBE -> { if(!(a.getUser() instanceof UsernamePasswordAuthenticationToken p)) throw new MessageDeliveryException("Unauthorized");
          String role=((User)p.getPrincipal()).role, d=String.valueOf(a.getDestination());
          if(!d.startsWith("/topic/")||(d.equals("/topic/admin")&&!role.equals("ADMIN"))||(d.equals("/topic/requests")&&!role.equals("DRIVER"))) throw new MessageDeliveryException("Forbidden"); }
        case SEND -> throw new MessageDeliveryException("Forbidden");
        default -> {}
      }
      return m; } }); }
}
