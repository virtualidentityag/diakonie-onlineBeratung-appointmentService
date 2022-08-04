package com.vi.appointmentservice.api.facade;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vi.appointmentservice.api.exception.httpresponses.BadRequestException;
import com.vi.appointmentservice.api.exception.httpresponses.InternalServerErrorException;
import com.vi.appointmentservice.api.exception.httpresponses.NotFoundException;
import com.vi.appointmentservice.api.model.AgencyConsultantSyncRequestDTO;
import com.vi.appointmentservice.api.model.AgencyMasterDataSyncRequestDTO;
import com.vi.appointmentservice.api.model.CalcomEventType;
import com.vi.appointmentservice.api.model.CalcomTeam;
import com.vi.appointmentservice.api.model.MeetingSlug;
import com.vi.appointmentservice.api.model.TeamEventTypeConsultant;
import com.vi.appointmentservice.api.service.calcom.CalComEventTypeService;
import com.vi.appointmentservice.api.service.calcom.CalComUserService;
import com.vi.appointmentservice.api.service.calcom.team.CalComTeamService;
import com.vi.appointmentservice.api.service.onlineberatung.AdminUserService;
import com.vi.appointmentservice.model.TeamToAgency;
import com.vi.appointmentservice.repository.CalcomUserToConsultantRepository;
import com.vi.appointmentservice.repository.EventTypeRepository;
import com.vi.appointmentservice.repository.MembershipsRepository;
import com.vi.appointmentservice.repository.TeamRepository;
import com.vi.appointmentservice.repository.TeamToAgencyRepository;
import com.vi.appointmentservice.repository.WebhookRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

/*
 * Facade to encapsulate agency operations
 */
@Component
@RequiredArgsConstructor
public class AgencyFacade {

  @NonNull
  private final CalComTeamService calComTeamService;
  @NonNull
  private final CalComEventTypeService calComEventTypeService;
  @NonNull
  private final TeamToAgencyRepository teamToAgencyRepository;
  @NonNull
  private final CalcomUserToConsultantRepository calcomUserToConsultantRepository;
  @NonNull
  private final MembershipsRepository membershipsRepository;
  @NonNull
  private final TeamRepository teamRepository;
  @NonNull
  private final EventTypeRepository eventTypeRepository;
  @NonNull
  private final WebhookRepository webhookRepository;
  @NonNull
  private final AdminUserService adminUserService;
  @NonNull
  private final EventTypeFacade eventTypeFacade;
  @NonNull
  private final CalComUserService calComUserService;

  public List<Long> getAllConsultantIdsOfAgency(Long agencyId){
    Optional<TeamToAgency> teamToAgency = teamToAgencyRepository.findByAgencyId(agencyId);
    if (teamToAgency.isPresent()) {
      Long teamId = teamToAgency.get().getTeamid();
      return membershipsRepository.getUsersOfTeam(teamId);
    } else {
      throw new NotFoundException("No teams associate to agency with id '" +agencyId+ "' found");
    }
  }


  public List<TeamEventTypeConsultant> getAllConsultantsOfAgency(Long agencyId){
    List<TeamEventTypeConsultant> availableConsultants = new ArrayList<>();
    for(Long userId : this.getAllConsultantIdsOfAgency(agencyId)){
      // TODO: refactor int Optional<CalcomUserToConsultant> to avoid double call
      if (calcomUserToConsultantRepository.existsByCalComUserId(userId)){
        TeamEventTypeConsultant teamEventTypeConsultant = new TeamEventTypeConsultant();
        teamEventTypeConsultant.setConsultantName(calComUserService.getUserById(userId).getName());
        teamEventTypeConsultant.setConsultantId(calcomUserToConsultantRepository.findByCalComUserId(userId).getConsultantId());
        availableConsultants.add(teamEventTypeConsultant);
      }
    }
    return availableConsultants;
  }

  protected Long getTeamIdByAgencyId(Long agencyId) {
    Optional<TeamToAgency> teamToAgency = teamToAgencyRepository.findByAgencyId(agencyId);
    Long teamid;
    if(teamToAgency.isPresent()){
      teamid = getTeamIdByAgencyId(agencyId);
    } else {
      throw new NotFoundException("No team for agency with the id '" + agencyId +  "'");
    }
    return teamid;
  }

  public MeetingSlug getMeetingSlugByAgencyId(Long agencyId) {
    this.checkIfAgencyTeamExists(agencyId);
    MeetingSlug meetingSlug = new MeetingSlug();
    meetingSlug.setSlug(calComTeamService.getTeamById(
        teamToAgencyRepository.findByAgencyId(agencyId).get().getTeamid()).getSlug());
    return meetingSlug;
  }



  private void checkIfAgencyTeamExists(Long agencyId) {
    if (!teamToAgencyRepository.existsByAgencyId(agencyId)) {
      throw new BadRequestException(
          String.format("No calcom team associated to agency with id: %s", agencyId));
    }
  }

  public void agencyConsultantsSync(AgencyConsultantSyncRequestDTO request) {
    String consultantId = request.getConsultantId();
    Long calComUserId = calcomUserToConsultantRepository.findByConsultantId(consultantId)
        .getCalComUserId();
    List<Long> teamIds = request.getAgencies().stream()
        .filter(teamToAgencyRepository::existsByAgencyId)
        .map(agencyId -> teamToAgencyRepository.findByAgencyId(agencyId).get().getTeamid())
        .collect(Collectors.toList());
    membershipsRepository.updateMemberShipsOfUser(calComUserId, teamIds);
    // Reset user teamEventTypeMemberships
    eventTypeRepository.removeTeamEventTypeMembershipsForUser(calComUserId);
    // Add consultant to team eventTypes
    for(Long teamId: teamIds){
      for(CalcomEventType eventType: calComEventTypeService.getAllEventTypesOfTeam(teamId)){
        eventTypeRepository.addTeamEventTypeMemberships(Long.valueOf(eventType.getId()), calComUserId);
      }
    }
    webhookRepository.updateUserWebhook(calComUserId);
  }

  public void agencyMasterDataSync(AgencyMasterDataSyncRequestDTO request) {
    Optional<TeamToAgency> teamToAgency = teamToAgencyRepository.findByAgencyId(request.getId());
    CalcomTeam createdOrUpdateTeam;
    if (teamToAgency.isEmpty()) {
      CalcomTeam team = new CalcomTeam();
      team.setName(request.getName());
      team.setHideBranding(true);
      createdOrUpdateTeam = calComTeamService.createTeam(team);
      Long teamId = createdOrUpdateTeam.getId();
      TeamToAgency entity = new TeamToAgency();
      entity.setTeamid(teamId);
      entity.setAgencyId(request.getId());
      teamToAgencyRepository.save(entity);
    } else {
      CalcomTeam team = new CalcomTeam();
      team.setName(request.getName());
      team.setId(teamToAgency.get().getTeamid());
      createdOrUpdateTeam = calComTeamService.editTeam(team);
    }
    // Create default team eventType if none exists
    if(createdOrUpdateTeam != null && calComEventTypeService.getAllEventTypesOfTeam(createdOrUpdateTeam.getId()).isEmpty()){
      ObjectMapper objectMapper = new ObjectMapper();
      // Ignore null values
      objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
      JSONObject eventTypePayloadJson = null;
      try {
        eventTypePayloadJson = new JSONObject(objectMapper.writeValueAsString(eventTypeFacade.getDefaultCalcomInitialMeetingEventType(createdOrUpdateTeam)));
      } catch (JsonProcessingException e) {
        throw new InternalServerErrorException("Could not serialize createCalcomUser payload");
      }
      calComEventTypeService.createEventType(eventTypePayloadJson);
    }
  }

  public void deleteAgency(Long agencyId) {
    Optional<TeamToAgency> teamToAgency = teamToAgencyRepository.findByAgencyId(agencyId);
    if(teamToAgency.isPresent()){
      Long teamId = teamToAgency.get().getTeamid();
      membershipsRepository.deleteTeamMemeberships(teamId);
      teamRepository.deleteTeam(teamId);
      // TODO: Delete event-types?
      // TODO: Cancel Bookings?
    }
  }
}
