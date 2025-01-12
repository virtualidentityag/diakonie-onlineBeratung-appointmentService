package com.vi.appointmentservice.api.service;

import com.vi.appointmentservice.api.facade.AppointmentType;
import org.springframework.stereotype.Service;

@Service
public class AppointmentService {

  private static final String DEFAULT_EVENT_DESCRIPTION =
      "Ratsuchende können zwischen vier Terminarten wählen. Das Beratungsteam versucht, diesen Wunsch zu erfüllen. "
          + "Als Alternative generieren wir immer einen Link zum Video-Call. "
          + "Die endgültige Abstimmung erfolgt per Chat auf unserer Plattform. "
          + "Loggen Sie sich also unbedingt vor einem Termin ein!";

  public AppointmentType createDefaultAppointmentType() {
    AppointmentType appointmentType = new AppointmentType();
    appointmentType.setAfterEventBuffer(10);
    appointmentType.setBeforeEventBuffer(0);
    appointmentType.setDescription(DEFAULT_EVENT_DESCRIPTION);
    appointmentType.setLength(50);
    appointmentType.setMinimumBookingNotice(240);
    appointmentType.setSlotInterval(15);
    return appointmentType;
  }

}