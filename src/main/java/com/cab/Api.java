package com.cab;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@RestController @RequestMapping("/api")
public class Api {
  static final double SHARE=0.8;   // driver share of each fare
  static final SecureRandom RNG=new SecureRandom();
  static final Pattern PHONE=Pattern.compile("^\\+?[0-9 ()-]{7,18}$"), PLATE=Pattern.compile("^[A-Z0-9 -]{3,15}$");
  static final List<String> ACTIVE=List.of("REQUESTED","ACCEPTED","ARRIVED","STARTED");
  static final Map<String,String> NEXT=Map.of("ACCEPTED","ARRIVED","ARRIVED","STARTED","STARTED","COMPLETED");
  static final Pattern EMAIL=Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,190}$");
  final RideTrackRepo track; final ObjectMapper om; final AuditRepo audit; final TicketRepo tickets; final UserRepo users; final RideRepo rides; final PasswordEncoder pe; final Tokens jwt; final SimpMessagingTemplate ws;
  final String dummy; final Map<String,long[]> fails=new ConcurrentHashMap<>(), hits=new ConcurrentHashMap<>();
  public Api(UserRepo u,RideRepo r,TicketRepo tk,AuditRepo au,ObjectMapper om,RideTrackRepo tk2,PasswordEncoder pe,Tokens j,SimpMessagingTemplate ws){ users=u; rides=r; tickets=tk; audit=au; this.om=om; track=tk2; this.pe=pe; jwt=j; this.ws=ws; dummy=pe.encode("dummy-password"); }

  static ResponseStatusException err(int c,String m){ return new ResponseStatusException(HttpStatus.valueOf(c),m); }
  static String s(Map<String,Object> b,String k,int max){ if(!(b.get(k) instanceof String v)||v.isBlank()||v.length()>max) throw err(400,"Invalid "+k); return v.trim(); }
  static String utm(Map<String,Object> b,String k){ if(!(b.get(k) instanceof String v)) return null; String w=v.replaceAll("[^A-Za-z0-9 _.+-]","").trim(); if(w.isEmpty()) return null; return w.length()>60?w.substring(0,60):w; }
  static String z(String s){ return s==null?"":s; }
  static String opt(Map<String,Object> b,String k,int max){ Object o=b.get(k); if(o==null) return null; if(!(o instanceof String v)||v.length()>max) throw err(400,"Invalid "+k); String w=v.trim(); return w.isEmpty()?null:w; }
  static double n(Map<String,Object> b,String k,double lo,double hi){ if(!(b.get(k) instanceof Number x)||Double.isNaN(x.doubleValue())||x.doubleValue()<lo||x.doubleValue()>hi) throw err(400,"Invalid "+k); return x.doubleValue(); }
  static double km(double a,double b,double c,double d){ double dl=Math.toRadians(c-a),dn=Math.toRadians(d-b);
    double x=Math.pow(Math.sin(dl/2),2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.pow(Math.sin(dn/2),2); return 12742*Math.asin(Math.sqrt(x)); }
  static double road(double a,double b,double c,double d){ return Math.round(km(a,b,c,d)*13)/10.0; }   // x1.3 road factor
  static double fare(double k){ return Math.round(Math.max(60,40+12*k)); }
  Ride get(Long id){ return rides.findById(id).orElseThrow(()->err(404,"Not found")); }
  Ride push(Ride r){ ws.convertAndSend("/topic/ride/"+r.id,Map.of("status",r.status)); return r; }
  Map<String,Object> session(User u){ return Map.of("token",jwt.make(u),"user",u); }

  // ---------- auth ----------
  @PostMapping("/auth/register") public Map<String,Object> register(@RequestBody Map<String,Object> b){
    String email=s(b,"email",254).toLowerCase(),role=s(b,"role",10),pw=s(b,"password",72);
    if(!EMAIL.matcher(email).matches()) throw err(400,"Invalid email");
    if(pw.length()<8) throw err(400,"Password must be 8+ characters");
    if(!role.equals("RIDER")&&!role.equals("DRIVER")) throw err(400,"Invalid role");   // never accept ADMIN from clients
    if(users.findByEmail(email).isPresent()) throw err(409,"Email already registered");
    User u=new User(); u.name=s(b,"name",60); u.email=email; u.role=role; u.password=pe.encode(pw); u.utmSource=utm(b,"utmSource"); u.utmMedium=utm(b,"utmMedium"); u.utmCampaign=utm(b,"utmCampaign"); users.save(u); return session(u); }
  @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody Map<String,Object> b){
    String email=s(b,"email",254).toLowerCase(); long now=System.currentTimeMillis();
    long[] f=fails.computeIfAbsent(email,k->new long[]{0,now}); if(now-f[1]>600000){ f[0]=0; f[1]=now; }
    if(f[0]>=5) throw err(429,"Too many attempts. Try again in a few minutes.");
    User u=users.findByEmail(email).orElse(null);
    boolean ok=pe.matches(s(b,"password",72),u==null?dummy:u.password)&&u!=null&&u.active;   // same cost either way
    if(!ok){ f[0]++; throw err(401,"Invalid credentials"); } f[0]=0; return session(u); }

  // ---------- rides ----------
  @PostMapping("/public/estimate") public Map<String,Object> publicEstimate(HttpServletRequest rq,@RequestBody Map<String,Object> b){   // fare check for visitors, rate limited
    long now=System.currentTimeMillis(); if(hits.size()>10000) hits.clear();
    long[] h=hits.computeIfAbsent(rq.getRemoteAddr(),k->new long[]{0,now});
    synchronized(h){ if(now-h[1]>60000){ h[0]=0; h[1]=now; } if(++h[0]>40) throw err(429,"Too many requests. Try again in a minute."); }
    return estimate(b); }
  @PostMapping("/rides/estimate") public Map<String,Object> estimate(@RequestBody Map<String,Object> b){
    double d=road(n(b,"pLat",-90,90),n(b,"pLng",-180,180),n(b,"dLat",-90,90),n(b,"dLng",-180,180));
    return Map.of("distanceKm",d,"fare",fare(d),"etaMin",Math.round(d*2)+2); }
  @PostMapping("/rides") @PreAuthorize("hasRole('RIDER')") public Ride book(@AuthenticationPrincipal User u,@RequestBody Map<String,Object> b){
    if(rides.existsByRiderIdAndStatusIn(u.id,ACTIVE)) throw err(409,"You already have an active ride");
    Ride r=new Ride(); r.riderId=u.id; r.pickup=s(b,"pickup",120); r.drop=s(b,"drop",120);
    r.pLat=n(b,"pLat",-90,90); r.pLng=n(b,"pLng",-180,180); r.dLat=n(b,"dLat",-90,90); r.dLng=n(b,"dLng",-180,180);
    r.distanceKm=road(r.pLat,r.pLng,r.dLat,r.dLng);                       // fare is ALWAYS computed server-side
    if(r.distanceKm<0.1||r.distanceKm>500) throw err(400,"Distance must be between 0.1 and 500 km");
    r.fare=fare(r.distanceKm); r.otp=String.format("%04d",RNG.nextInt(10000)); rides.save(r); ws.convertAndSend("/topic/requests",Map.of("id",r.id)); return r; }
  @GetMapping("/rides/mine") public List<Map<String,Object>> mine(@AuthenticationPrincipal User u){
    boolean d=u.role.equals("DRIVER"); List<Ride> l=d?rides.findByDriverIdOrderByIdDesc(u.id):rides.findByRiderIdOrderByIdDesc(u.id);
    return l.stream().map(r->{ Map<String,Object> m=om.convertValue(r,new TypeReference<Map<String,Object>>(){}); if(d) m.put("earning",Math.round(r.fare*SHARE)); return m; }).toList(); }
  @GetMapping("/rides/{id}") public Map<String,Object> one(@AuthenticationPrincipal User u,@PathVariable Long id){
    Ride r=get(id); if(!(u.id.equals(r.riderId)||u.id.equals(r.driverId)||u.role.equals("ADMIN"))) throw err(404,"Not found");   // IDOR guard
    Map<String,Object> m=new HashMap<>(); m.put("ride",r);
    if(r.driverId!=null){ User d=users.findById(r.driverId).get(); m.put("driver",Map.of("name",d.name,"rating",d.rating,"count",d.ratingCount,"lat",d.lat,"lng",d.lng,"vehicle",z(d.vehicle),"plate",z(d.plate),"color",z(d.color),"at",d.locAt==null?"":d.locAt.toString())); }
    if(!u.id.equals(r.riderId)) m.put("rider",Map.of("name",users.findById(r.riderId).get().name));   // driver and admin see the rider name
    if(u.id.equals(r.driverId)) m.put("earning",Math.round(r.fare*SHARE));                       // what the driver actually gets
    if(u.id.equals(r.riderId)&&r.otp!=null&&ACTIVE.contains(r.status)) m.put("otp",r.otp);        // trip code is shown to the rider only
    return m; }
  @PostMapping("/rides/{id}/cancel") public Ride cancel(@AuthenticationPrincipal User u,@PathVariable Long id){
    Ride r=get(id); if(!(u.id.equals(r.riderId)||u.id.equals(r.driverId))) throw err(404,"Not found");
    if(!List.of("REQUESTED","ACCEPTED","ARRIVED").contains(r.status)) throw err(409,"Cannot cancel now");
    r.status="CANCELLED"; r.cancelledAt=Instant.now(); r.cancelledBy=u.role; return push(rides.save(r)); }
  @PostMapping("/rides/{id}/pay") @PreAuthorize("hasRole('RIDER')") public Ride pay(@AuthenticationPrincipal User u,@PathVariable Long id){
    Ride r=get(id); if(!u.id.equals(r.riderId)) throw err(404,"Not found");
    if(!"COMPLETED".equals(r.status)||r.paid) throw err(409,"Nothing to pay"); r.paid=true; return rides.save(r); }   // demo gateway: plug Stripe PaymentIntent here
  @PostMapping("/rides/{id}/rate") @PreAuthorize("hasRole('RIDER')") @Transactional public Ride rate(@AuthenticationPrincipal User u,@PathVariable Long id,@RequestBody Map<String,Object> b){
    Ride r=get(id); if(!u.id.equals(r.riderId)) throw err(404,"Not found");
    if(!"COMPLETED".equals(r.status)||r.rating!=null) throw err(409,"Cannot rate");
    int v=(int)n(b,"rating",1,5); r.rating=v; User d=users.findById(r.driverId).get();
    d.rating=(d.rating*d.ratingCount+v)/(d.ratingCount+1); d.ratingCount++; users.save(d); return rides.save(r); }
  @PostMapping("/rides/{id}/sos") public Map<String,Object> sos(@AuthenticationPrincipal User u,@PathVariable Long id){
    Ride r=get(id); if(!(u.id.equals(r.riderId)||u.id.equals(r.driverId))) throw err(404,"Not found");
    r.sos=true; rides.save(r); ws.convertAndSend("/topic/admin",Map.of("sos",id)); return Map.of("ok",true); }

  // ---------- driver ----------
  @PostMapping("/driver/status") @PreAuthorize("hasRole('DRIVER')") public Map<String,Object> status(@AuthenticationPrincipal User u,@RequestBody Map<String,Object> b){
    User d=users.findById(u.id).get();
    if(Boolean.TRUE.equals(b.get("available"))&&(d.vehicle==null||d.plate==null)) throw err(409,"Add your vehicle details in Profile before going online");
    d.lat=n(b,"lat",-90,90); d.lng=n(b,"lng",-180,180);
    if(b.get("available") instanceof Boolean a) d.available=a; d.locAt=Instant.now(); users.save(d);
    rides.findFirstByDriverIdAndStatusIn(u.id,ACTIVE).ifPresent(r->{   // route trail, kept while a ride is active
      var last=track.findTopByRideIdOrderByIdDesc(r.id);
      if(last.isEmpty()||last.get().lat!=d.lat||last.get().lng!=d.lng){ RideTrack p=new RideTrack(); p.rideId=r.id; p.lat=d.lat; p.lng=d.lng; p.status=r.status; track.save(p); } });
    return Map.of("available",d.available); }
  @GetMapping("/driver/requests") @PreAuthorize("hasRole('DRIVER')") public List<Ride> requests(@AuthenticationPrincipal User u){
    User d=users.findById(u.id).get(); if(!d.available) return List.of();
    return rides.findByStatus("REQUESTED").stream().filter(r->km(d.lat,d.lng,r.pLat,r.pLng)<=20).toList(); }
  @PostMapping("/rides/{id}/accept") @PreAuthorize("hasRole('DRIVER')") @Transactional public Ride accept(@AuthenticationPrincipal User u,@PathVariable Long id){
    if(!users.findById(u.id).get().available) throw err(409,"Go online first");
    if(rides.existsByDriverIdAndStatusIn(u.id,ACTIVE)) throw err(409,"Finish your current ride first");
    if(rides.claim(id,u.id,Instant.now())==0) throw err(409,"Ride no longer available");   // atomic: only one driver can win
    return push(get(id)); }
  @PostMapping("/rides/{id}/advance") @PreAuthorize("hasRole('DRIVER')") public Ride advance(@AuthenticationPrincipal User u,@PathVariable Long id,@RequestBody(required=false) Map<String,Object> b){
    Ride r=get(id); if(!u.id.equals(r.driverId)) throw err(404,"Not found");
    if(r.status.equals("ARRIVED")&&r.otp!=null){   // the trip starts only with the rider's code
      if(r.otpFails>=5) throw err(429,"Too many wrong codes. Ask the rider to cancel and book again.");
      String given=""; if(b!=null&&b.get("otp") instanceof String o) given=o.trim();
      if(!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),r.otp.getBytes(StandardCharsets.UTF_8))){ r.otpFails++; rides.save(r); throw err(403,"Wrong code. "+Math.max(0,5-r.otpFails)+" tries left"); } }
    String nx=NEXT.get(r.status); if(nx==null) throw err(409,"Cannot advance"); r.status=nx; Instant now=Instant.now(); if(nx.equals("ARRIVED")) r.arrivedAt=now; if(nx.equals("STARTED")) r.startedAt=now; if(nx.equals("COMPLETED")) r.completed=now; return push(rides.save(r)); }
  @GetMapping("/driver/stats") @PreAuthorize("hasRole('DRIVER')") public Map<String,Object> dstats(@AuthenticationPrincipal User u){
    var done=rides.findByDriverIdOrderByIdDesc(u.id).stream().filter(r->r.status.equals("COMPLETED")).toList(); User d=users.findById(u.id).get();
    return Map.of("trips",done.size(),"earnings",Math.round(done.stream().mapToDouble(r->r.fare).sum()*SHARE),"rating",d.rating,"ratingCount",d.ratingCount,"available",d.available,"lat",d.lat,"lng",d.lng); }

  // ---------- profile ----------
  @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal User u){
    User x=users.findById(u.id).get(); boolean d=x.role.equals("DRIVER");
    var mine=d?rides.findByDriverIdOrderByIdDesc(x.id):rides.findByRiderIdOrderByIdDesc(x.id);
    var done=mine.stream().filter(r->r.status.equals("COMPLETED")).toList();
    double sum=d?done.stream().mapToDouble(r->r.fare).sum()*SHARE:mine.stream().filter(r->r.paid).mapToDouble(r->r.fare).sum();
    return Map.of("user",x,"rides",mine.size(),"completed",done.size(),"amount",Math.round(sum)); }
  @PostMapping("/me") public User updateMe(@AuthenticationPrincipal User u,@RequestBody Map<String,Object> b){
    User x=users.findById(u.id).get(); x.name=s(b,"name",60);
    String ph=opt(b,"phone",18); if(ph!=null&&!PHONE.matcher(ph).matches()) throw err(400,"Invalid phone number"); x.phone=ph;
    if(x.role.equals("DRIVER")){ x.vehicle=opt(b,"vehicle",40); x.color=opt(b,"color",20); String pl=opt(b,"plate",15);
      if(pl!=null){ pl=pl.toUpperCase(); if(!PLATE.matcher(pl).matches()) throw err(400,"Invalid number plate"); } x.plate=pl;
      if(x.vehicle==null||x.plate==null) x.available=false; }
    return users.save(x); }
  @PostMapping("/me/password") public Map<String,Object> changePassword(@AuthenticationPrincipal User u,@RequestBody Map<String,Object> b){
    User x=users.findById(u.id).get();
    if(!pe.matches(s(b,"current",72),x.password)) throw err(403,"Current password is wrong");
    String np=s(b,"password",72); if(np.length()<8) throw err(400,"Password must be 8+ characters");
    x.password=pe.encode(np); x.tokenVersion++; users.save(x); return session(x); }   // old tokens on other devices stop working

  // ---------- support ----------
  @PostMapping("/tickets") public Ticket ticket(@AuthenticationPrincipal User u,@RequestBody Map<String,Object> b){
    if(tickets.countByUserIdAndStatus(u.id,"OPEN")>=5) throw err(429,"You already have 5 open messages. Please wait for a reply.");
    Ticket t=new Ticket(); t.userId=u.id; t.userName=u.name; t.subject=s(b,"subject",80); t.message=s(b,"message",1000); return tickets.save(t); }
  @GetMapping("/tickets") public List<Ticket> myTickets(@AuthenticationPrincipal User u){ return tickets.findByUserIdOrderByIdDesc(u.id); }
  @GetMapping("/admin/tickets") @PreAuthorize("hasRole('ADMIN')") public List<Ticket> allTickets(){ return tickets.findAllByOrderByIdDesc(); }
  @PostMapping("/admin/tickets/{id}/close") @PreAuthorize("hasRole('ADMIN')") public Ticket closeTicket(@AuthenticationPrincipal User me,@PathVariable Long id,@RequestBody Map<String,Object> b){
    Ticket t=tickets.findById(id).orElseThrow(()->err(404,"Not found")); t.reply=s(b,"reply",1000); t.status="CLOSED"; log(me,"TICKET_CLOSED","#"+t.id+" from "+t.userName); return tickets.save(t); }

  // ---------- admin ----------
  @GetMapping("/admin/stats") @PreAuthorize("hasRole('ADMIN')") public Map<String,Object> astats(){
    var all=rides.findAll(); Map<String,Object> m=new LinkedHashMap<>();
    m.put("riders",users.countByRole("RIDER")); m.put("drivers",users.countByRole("DRIVER")); m.put("rides",all.size());
    m.put("completed",all.stream().filter(r->r.status.equals("COMPLETED")).count());
    m.put("revenue",all.stream().filter(r->r.paid).mapToDouble(r->r.fare).sum()); m.put("sos",all.stream().filter(r->r.sos).count());
    long pay=all.stream().filter(r->r.paid).mapToLong(r->Math.round(r.fare*SHARE)).sum();
    m.put("payouts",pay); m.put("platform",Math.round(all.stream().filter(r->r.paid).mapToDouble(r->r.fare).sum())-pay);
    m.put("pending",Math.round(all.stream().filter(r->r.status.equals("COMPLETED")&&!r.paid).mapToDouble(r->r.fare).sum()));
    m.put("onlineDrivers",users.findAll().stream().filter(x->x.role.equals("DRIVER")&&x.available).count());
    m.put("activeRides",all.stream().filter(r->ACTIVE.contains(r.status)).count());
    m.put("openTickets",tickets.findAllByOrderByIdDesc().stream().filter(t->t.status.equals("OPEN")).count()); return m; }
  @GetMapping("/admin/users") @PreAuthorize("hasRole('ADMIN')") public List<User> allUsers(){ return users.findAll(Sort.by("id")); }
  @GetMapping("/admin/rides") @PreAuthorize("hasRole('ADMIN')") public List<Map<String,Object>> allRides(){
    Map<Long,User> by=new HashMap<>(); users.findAll().forEach(x->by.put(x.id,x)); List<Map<String,Object>> out=new ArrayList<>();
    for(Ride r:rides.findAll(Sort.by("id").descending())){
      Map<String,Object> m=new LinkedHashMap<>(); User rd=by.get(r.riderId), dr=r.driverId==null?null:by.get(r.driverId);
      m.put("id",r.id); m.put("driverId",r.driverId); m.put("pLat",r.pLat); m.put("pLng",r.pLng); m.put("dLat",r.dLat); m.put("dLng",r.dLng); m.put("pickup",r.pickup); m.put("drop",r.drop); m.put("fare",r.fare); m.put("distanceKm",r.distanceKm); m.put("status",r.status);
      long ern=Math.round(r.fare*SHARE); m.put("earning",r.paid?ern:null); m.put("profit",r.paid?Math.round(r.fare)-ern:null);
      m.put("paid",r.paid); m.put("sos",r.sos); m.put("rating",r.rating); m.put("created",r.created);
      m.put("riderName",rd==null?"":rd.name); m.put("riderPhone",rd==null?"":z(rd.phone)); m.put("driverName",dr==null?"":dr.name); m.put("driverPhone",dr==null?"":z(dr.phone));
      if(r.sos&&dr!=null){ m.put("driverLat",dr.lat); m.put("driverLng",dr.lng); }
      out.add(m); }
    return out; }
  @PostMapping("/admin/rides/{id}/cancel") @PreAuthorize("hasRole('ADMIN')") public Ride adminCancel(@AuthenticationPrincipal User me,@PathVariable Long id){
    Ride r=get(id); if(!ACTIVE.contains(r.status)) throw err(409,"Only active rides can be cancelled");
    r.status="CANCELLED"; r.cancelledAt=Instant.now(); r.cancelledBy="ADMIN"; log(me,"RIDE_CANCELLED","Ride #"+id); return push(rides.save(r)); }
  @PostMapping("/admin/rides/{id}/sos/resolve") @PreAuthorize("hasRole('ADMIN')") public Ride resolveSos(@AuthenticationPrincipal User me,@PathVariable Long id){
    Ride r=get(id); if(!r.sos) throw err(409,"No active SOS on this ride"); r.sos=false; log(me,"SOS_RESOLVED","Ride #"+id); return rides.save(r); }
  @GetMapping("/admin/audit") @PreAuthorize("hasRole('ADMIN')") public List<Audit> auditLog(){ return audit.findTop30ByOrderByIdDesc(); }
  void log(User a,String act,String d){ Audit e=new Audit(); e.admin=a.name; e.action=act; e.detail=d.length()>300?d.substring(0,300):d; audit.save(e); }
  @GetMapping("/admin/rides/{id}") @PreAuthorize("hasRole('ADMIN')") public Map<String,Object> adminRide(@PathVariable Long id){
    Ride r=get(id); Map<String,Object> m=new LinkedHashMap<>(); m.put("ride",om.convertValue(r,new TypeReference<Map<String,Object>>(){}));
    m.put("rider",person(users.findById(r.riderId).orElse(null))); m.put("driver",person(r.driverId==null?null:users.findById(r.driverId).orElse(null)));
    long ern=Math.round(r.fare*SHARE); m.put("driverShare",ern); m.put("profit",r.paid?Math.round(r.fare)-ern:null);
    m.put("track",track.findByRideIdOrderByIdAsc(id).stream().map(p->Map.of("lat",p.lat,"lng",p.lng,"at",p.ts.toString(),"status",String.valueOf(p.status))).toList()); return m; }
  Map<String,Object> person(User x){ if(x==null) return null; Map<String,Object> p=new LinkedHashMap<>(); p.put("name",x.name); p.put("email",x.email); p.put("phone",z(x.phone));
    if(x.role.equals("DRIVER")){ p.put("vehicle",z(x.vehicle)); p.put("color",z(x.color)); p.put("plate",z(x.plate)); p.put("rating",x.rating); p.put("ratingCount",x.ratingCount);
      p.put("lat",x.lat); p.put("lng",x.lng); p.put("at",x.locAt==null?"":x.locAt.toString()); p.put("online",x.available); } return p; }
  @PostMapping("/admin/users/{id}/active") @PreAuthorize("hasRole('ADMIN')") public Map<String,Object> active(@AuthenticationPrincipal User me,@PathVariable Long id,@RequestBody Map<String,Object> b){
    User t=users.findById(id).orElseThrow(()->err(404,"Not found")); if(t.role.equals("ADMIN")||t.id.equals(me.id)) throw err(403,"Cannot change an admin");
    t.active=Boolean.TRUE.equals(b.get("active")); if(!t.active) t.available=false; log(me,t.active?"UNBAN":"BAN",t.name+" ("+t.email+")"); users.save(t); return Map.of("active",t.active); }
}
