package com.tanjian.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tanjian.dto.ReservationDTO;
import com.tanjian.dto.Result;
import com.tanjian.entity.Reservation;

public interface IReservationService extends IService<Reservation> {
    Result createReservation(ReservationDTO dto);
    Result queryMyReservations(Integer current);
    Result queryMyReservation(Long id);
    Result cancelMyReservation(Long id);
    Result queryManagedReservations(Integer current);
    Result decideManagedReservation(Long id, boolean confirm);
}
