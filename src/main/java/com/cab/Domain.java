package com.cab;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="users") class User {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
  String name; @Column(unique=true,nullable=false) String email;
  @JsonIgnore String password; String role; boolean active=true;
  double rating=5; int ratingCount=0; boolean available=false; double lat, lng; Instant created=Instant.now();
  String phone, vehicle, plate, color; Instant locAt; String utmSource, utmMedium, utmCampaign;
  @JsonIgnore @Column(columnDefinition="integer default 0 not null") int tokenVersion;
}
@Entity class Ride {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id; @Version Long version;
  Long riderId, driverId; String pickup; @Column(name="drop_address") String drop; double pLat,pLng,dLat,dLng,distanceKm,fare;
  String status="REQUESTED"; boolean paid, sos; Integer rating; Instant created=Instant.now(), completed;
  @JsonIgnore String otp; @JsonIgnore @Column(columnDefinition="integer default 0 not null") int otpFails;
  Instant acceptedAt, arrivedAt, startedAt, cancelledAt; String cancelledBy;
}
interface UserRepo extends JpaRepository<User,Long> { Optional<User> findByEmail(String e); long countByRole(String r); }
interface RideRepo extends JpaRepository<Ride,Long> {
  List<Ride> findByRiderIdOrderByIdDesc(Long id); List<Ride> findByDriverIdOrderByIdDesc(Long id); List<Ride> findByStatus(String s);
  Optional<Ride> findFirstByDriverIdAndStatusIn(Long id,Collection<String> s);
  boolean existsByRiderIdAndStatusIn(Long id,Collection<String> s); boolean existsByDriverIdAndStatusIn(Long id,Collection<String> s);
  @Modifying(clearAutomatically=true) @Query("update Ride r set r.driverId=:d, r.status='ACCEPTED', r.acceptedAt=:t where r.id=:id and r.status='REQUESTED'")
  int claim(@Param("id") Long id,@Param("d") Long d,@Param("t") Instant t);
}
@Entity class Ticket {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id; Long userId; String userName; String subject;
  @Column(length=1000) String message; @Column(length=1000) String reply; String status="OPEN"; Instant created=Instant.now();
}
interface TicketRepo extends JpaRepository<Ticket,Long> {
  List<Ticket> findByUserIdOrderByIdDesc(Long id); List<Ticket> findAllByOrderByIdDesc(); long countByUserIdAndStatus(Long id,String s);
}
@Entity class Audit {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id; String admin, action; @Column(length=300) String detail; Instant created=Instant.now();
}
interface AuditRepo extends JpaRepository<Audit,Long> { List<Audit> findTop30ByOrderByIdDesc(); }
@Entity class RideTrack {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id; Long rideId; double lat, lng; String status; Instant ts=Instant.now();
}
interface RideTrackRepo extends JpaRepository<RideTrack,Long> { List<RideTrack> findByRideIdOrderByIdAsc(Long id); Optional<RideTrack> findTopByRideIdOrderByIdDesc(Long id); }
